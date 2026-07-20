package com.tunnel.control;

import com.tunnel.control.strategy.LoadStrategy;
import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.TunnelConstants;
import com.tunnel.protocol.dto.AssignmentRequest;
import com.tunnel.protocol.dto.AssignmentResponse;
import com.tunnel.protocol.dto.Session;
import com.tunnel.protocol.dto.SessionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;

/**
 * The Coordinator's brain (PRD-v2.md §4 path A, §10): issue a cryptographically
 * random session id + a stable subdomain, pick a pod via the configured
 * {@link LoadStrategy}, and write the session ownership record to Redis with a
 * TTL. This is the single place assignment happens — once per session.
 */
@Service
public class SessionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SessionCoordinator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final PodDirectory podDirectory;
    private final SubdomainGenerator subdomains;
    private final ControlProperties props;
    private final MeterRegistry meters;
    private final String kafkaTopicPrefix;
    private final Map<String, LoadStrategy> strategies = new LinkedHashMap<>();
    @Nullable
    private final IngressManager ingressManager;
    @Nullable
    private final KafkaManager kafkaManager;

    public SessionCoordinator(StringRedisTemplate redis, PodDirectory podDirectory,
                              SubdomainGenerator subdomains, ControlProperties props,
                              MeterRegistry meters, List<LoadStrategy> loadStrategies,
                              Optional<IngressManager> ingressManager,
                              Optional<KafkaManager> kafkaManager,
                              @Value("${control.kafka-topic-prefix:notifications_}") String kafkaTopicPrefix) {
        this.redis = redis;
        this.podDirectory = podDirectory;
        this.subdomains = subdomains;
        this.props = props;
        this.meters = meters;
        this.ingressManager = ingressManager.orElse(null);
        this.kafkaManager = kafkaManager.orElse(null);
        this.kafkaTopicPrefix = kafkaTopicPrefix;
        for (LoadStrategy s : loadStrategies) {
            strategies.put(s.name(), s);
        }
    }

    private void count(String outcome) {
        Counter.builder("tunnel_assignments_total")
                .description("Coordinator pod-assignment outcomes")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    /**
     * Create a new session (or reuse the client's existing id on reconnect) and
     * assign it to a live pod. The session is bound to {@code ownerId} (derived
     * from the caller's validated token — §9.2.3), enforcing ownership and the
     * per-owner session quota (§9.2.6).
     */
    public AssignmentResponse assign(AssignmentRequest req, String ownerId) {
        boolean reconnect = req.existingSessionId() != null;
        String sessionId = reconnect ? req.existingSessionId() : newSessionId();
        String subdomain = subdomains.forSession(sessionId);

        // Ownership boundary: only the owning token may reattach to a session.
        if (reconnect) {
            String existingOwner = currentOwner(sessionId);
            if (existingOwner != null && !existingOwner.equals(ownerId)) {
                log.warn("owner mismatch: '{}' tried to reattach session {} owned by '{}'",
                        ownerId, Session.redact(sessionId), existingOwner);
                count("owner-mismatch");
                return AssignmentResponse.rejected(ErrorCodes.OWNER_MISMATCH);
            }
        } else if (liveSessionCount(ownerId) >= props.maxSessionsPerOwner()) {
            log.warn("quota exceeded: owner '{}' already holds {} sessions",
                    ownerId, props.maxSessionsPerOwner());
            count("quota-exceeded");
            return AssignmentResponse.rejected(ErrorCodes.QUOTA_EXCEEDED);
        }

        List<PodInfo> pods = podDirectory.livePods();
        LoadStrategy strategy = strategies.getOrDefault(props.loadStrategy(),
                strategies.get("least-connections"));
        Optional<PodInfo> chosen = strategy.select(pods);
        if (chosen.isEmpty()) {
            log.warn("no pod available to assign session {} ({} live pods)",
                    Session.redact(sessionId), pods.size());
            count("no-pod-available");
            return AssignmentResponse.rejected(ErrorCodes.NO_POD_AVAILABLE);
        }

        PodInfo pod = chosen.get();

        // Ingress and Kafka topic creation are async — both involve external calls that
        // can take seconds. The assignment response is returned immediately.
        if (req.createIngress() && ingressManager != null) {
            final String sid = sessionId;
            final String sub = subdomain;
            Thread.ofVirtual().name("ingress-create-" + Session.redact(sessionId)).start(() -> {
                try {
                    String name = ingressManager.createIngress(sid, sub, req.pathPatterns());
                    // Patch ingress name into the session record once created.
                    redis.opsForHash().put(RedisKeys.session(sid), RedisKeys.F_INGRESS_NAME, name);
                } catch (RuntimeException e) {
                    log.warn("ingress creation failed for session {}: {}", Session.redact(sid), e.toString());
                }
            });
        }

        if (kafkaManager != null) {
            final String sid = sessionId;
            Thread.ofVirtual().name("kafka-topic-create-" + Session.redact(sessionId)).start(() -> {
                try {
                    kafkaManager.createTopic(kafkaTopicPrefix + sid);
                } catch (RuntimeException e) {
                    log.warn("kafka topic creation failed for session {}: {}", Session.redact(sid), e.toString());
                }
            });
        }

        writeSession(sessionId, subdomain, ownerId, pod.id(), req.pathPatterns(), null);
        count(reconnect ? "reassigned" : "assigned");

        log.info("{} session {} → subdomain {} → {} (strategy {}, {} live pods, pod conns {})",
                reconnect ? "reassigned" : "assigned",
                Session.redact(sessionId), subdomain, pod.id(),
                strategy.name(), pods.size(), pod.conns());

        String wsUrl = (props.tunnelUrl() != null && !props.tunnelUrl().isBlank())
                ? props.tunnelUrl() : pod.wsUrl();
        return AssignmentResponse.ok(
                sessionId, subdomain, pod.id(), wsUrl, props.heartbeatIntervalMs(), req.pathPatterns());
    }

    /**
     * Explicitly closes a session: deletes its temporary ingress (if any) and
     * removes the session key from Redis immediately rather than waiting for TTL expiry.
     * No-op if the session is already gone.
     */
    public boolean deleteSession(String sessionId, String ownerId) {
        String key = RedisKeys.session(sessionId);
        String existingOwner = currentOwner(sessionId);
        if (existingOwner != null && !existingOwner.equals(ownerId)) {
            log.warn("owner mismatch: '{}' tried to delete session {} owned by '{}'",
                    ownerId, Session.redact(sessionId), existingOwner);
            count("delete-owner-mismatch");
            return false;
        }
        Object ingressNameObj = redis.opsForHash().get(key, RedisKeys.F_INGRESS_NAME);
        if (ingressNameObj != null && ingressManager != null) {
            ingressManager.deleteIngress(sessionId);
        }
        if (kafkaManager != null) {
            kafkaManager.deleteTopic(kafkaTopicPrefix + sessionId);
        }
        redis.delete(key);
        log.info("session deleted: {}", Session.redact(sessionId));
        return true;
    }

    private void writeSession(String sessionId, String subdomain, String ownerId, String podId,
                              String pathPatterns, String ingressName) {
        String key = RedisKeys.session(sessionId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisKeys.F_OWNER_ID, nz(ownerId));
        fields.put(RedisKeys.F_SUBDOMAIN, subdomain);
        fields.put(RedisKeys.F_POD_ID, podId);
        fields.put(RedisKeys.F_STATUS, SessionStatus.PENDING.name());
        fields.put(RedisKeys.F_CREATED_AT, String.valueOf(System.currentTimeMillis()));
        if (pathPatterns != null && !pathPatterns.isBlank()) {
            fields.put(RedisKeys.F_PATH_PATTERNS, pathPatterns);
        }
        if (ingressName != null) {
            fields.put(RedisKeys.F_INGRESS_NAME, ingressName);
        }

        Duration ttl = Duration.ofMillis(props.sessionTtlMs());
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);

        // Reverse index for Host/subdomain-based routing at the Router.
        redis.opsForValue().set(RedisKeys.subdomainIndex(subdomain), sessionId, ttl);

        if (ownerId != null && !ownerId.isBlank()) {
            redis.opsForSet().add(RedisKeys.ownerSessions(ownerId), sessionId);
        }
    }

    /** Current owner recorded for a session, or null if the session is gone. */
    private String currentOwner(String sessionId) {
        Object owner = redis.opsForHash().get(RedisKeys.session(sessionId), RedisKeys.F_OWNER_ID);
        return owner == null ? null : owner.toString();
    }

    /**
     * Count an owner's still-live sessions, pruning expired ids from the owner set
     * as we go (the set members don't auto-expire with the session keys).
     */
    private long liveSessionCount(String ownerId) {
        var members = redis.opsForSet().members(RedisKeys.ownerSessions(ownerId));
        if (members == null || members.isEmpty()) {
            return 0;
        }
        long live = 0;
        for (String sid : members) {
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.session(sid)))) {
                live++;
            } else {
                redis.opsForSet().remove(RedisKeys.ownerSessions(ownerId), sid);
            }
        }
        return live;
    }

    private static String newSessionId() {
        byte[] bytes = new byte[TunnelConstants.SESSION_ID_BITS / 8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
