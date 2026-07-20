package com.tunnel.router;

import com.tunnel.protocol.RedisKeys;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The sticky lookup (PRD-v2.md §10, path B): resolve a session to the exact pod
 * that holds its socket. Stateless and O(1) — the Router stays horizontally
 * scalable. A miss (expired/unknown session, or a pod whose liveness key lapsed)
 * yields empty, which the filter turns into {@code tunnel-not-found}.
 */
@Component
public class PodResolver {

    /** Where a request should be forwarded. */
    public record PodTarget(String podId, String host, int port, boolean secure) {
        public String baseUrl() {
            return (secure ? "https://" : "http://") + host + ":" + port;
        }
    }

    private final StringRedisTemplate redis;

    public PodResolver(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Resolve a subdomain (Host-based routing) to its session id. */
    public Optional<String> sessionForSubdomain(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.subdomainIndex(subdomain)));
    }

    /** Resolve a session id to its owning pod's address. */
    public Optional<PodTarget> targetForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Object podId = redis.opsForHash().get(RedisKeys.session(sessionId), RedisKeys.F_POD_ID);
        if (podId == null) {
            return Optional.empty();
        }
        String podKey = RedisKeys.pod(podId.toString());
        Object host = redis.opsForHash().get(podKey, RedisKeys.F_HOST);
        Object port = redis.opsForHash().get(podKey, RedisKeys.F_PORT);
        if (host == null || port == null) {
            return Optional.empty(); // pod's liveness key has expired
        }
        boolean secure = Boolean.parseBoolean(String.valueOf(redis.opsForHash().get(podKey, RedisKeys.F_SECURE)));
        return Optional.of(new PodTarget(
                podId.toString(), host.toString(), Integer.parseInt(port.toString()), secure));
    }
}
