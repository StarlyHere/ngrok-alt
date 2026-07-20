package com.tunnel.pod;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.dto.SessionStatus;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * The pod's Redis interactions (PRD-v2.md §10): pod self-registration with a TTL
 * heartbeat, per-pod connection counters for Least-Connections assignment, and
 * refreshing a session's ownership TTL — the mechanism that turns a missed
 * heartbeat into a clean {@code tunnel-not-found} (PRD-v2.md §4.3).
 */
@Service
public class PodRedisService {

    private static final Logger log = LoggerFactory.getLogger(PodRedisService.class);

    private final StringRedisTemplate redis;
    private final PodProperties props;

    public PodRedisService(StringRedisTemplate redis, PodProperties props) {
        this.redis = redis;
        this.props = props;
    }

    // --- pod liveness + load ---

    /** Register this pod with conns=0 and a liveness TTL. */
    public void registerPod() {
        writePodRecord(true);
        log.info("pod {} registered in redis ({}:{})", props.id(), props.host(), props.port());
    }

    /**
     * Refresh this pod's complete liveness record (scheduled self-heartbeat).
     *
     * <p>Writing every routable field is intentional. If Redis expires the key
     * during a scheduler, network, or JVM pause, a lastSeen-only heartbeat would
     * recreate an unusable hash with no host or port. putIfAbsent preserves the
     * live connection count while allowing an expired key to self-heal.
     */
    public void heartbeatPod() {
        writePodRecord(false);
    }

    private void writePodRecord(boolean resetConnections) {
        String key = RedisKeys.pod(props.id());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisKeys.F_HOST, props.host());
        fields.put(RedisKeys.F_PORT, String.valueOf(props.port()));
        fields.put(RedisKeys.F_SECURE, String.valueOf(props.secure()));
        fields.put(RedisKeys.F_LAST_SEEN, String.valueOf(System.currentTimeMillis()));
        if (resetConnections) {
            fields.put(RedisKeys.F_CONNS, "0");
        }
        redis.opsForHash().putAll(key, fields);
        if (!resetConnections) {
            redis.opsForHash().putIfAbsent(key, RedisKeys.F_CONNS, "0");
        }
        redis.expire(key, Duration.ofMillis(props.podTtlMs()));
    }

    /** Remove this pod from the pool (graceful shutdown). */
    public void deregisterPod() {
        redis.delete(RedisKeys.pod(props.id()));
        log.info("pod {} deregistered from redis", props.id());
    }

    public void incrementConns() {
        redis.opsForHash().increment(RedisKeys.pod(props.id()), RedisKeys.F_CONNS, 1);
    }

    public void decrementConns() {
        redis.opsForHash().increment(RedisKeys.pod(props.id()), RedisKeys.F_CONNS, -1);
    }

    // --- session ownership TTL ---

    /** The owner recorded for a session (written by the Coordinator), if present. */
    public java.util.Optional<String> sessionOwner(String sessionId) {
        Object owner = redis.opsForHash().get(RedisKeys.session(sessionId), RedisKeys.F_OWNER_ID);
        return java.util.Optional.ofNullable(owner).map(Object::toString);
    }

    /** Mark a session ACTIVE on this pod and (re)apply its ownership TTL. */
    public void activateSession(String sessionId) {
        String key = RedisKeys.session(sessionId);
        redis.opsForHash().put(key, RedisKeys.F_STATUS, SessionStatus.ACTIVE.name());
        redis.opsForHash().put(key, RedisKeys.F_POD_ID, props.id());
        refreshSessionTtl(sessionId);
    }

    /**
     * Refresh the session ownership TTL (and its subdomain index) — called on each
     * client heartbeat. When heartbeats stop, the key expires and the Router
     * returns {@code tunnel-not-found}.
     */
    public void refreshSessionTtl(String sessionId) {
        String key = RedisKeys.session(sessionId);
        Duration ttl = Duration.ofMillis(props.sessionTtlMs());
        Boolean exists = redis.hasKey(key);
        if (Boolean.FALSE.equals(exists)) {
            return; // already expired; nothing to refresh
        }
        redis.expire(key, ttl);
        Object subdomain = redis.opsForHash().get(key, RedisKeys.F_SUBDOMAIN);
        if (subdomain != null) {
            redis.expire(RedisKeys.subdomainIndex(subdomain.toString()), ttl);
        }
    }
}
