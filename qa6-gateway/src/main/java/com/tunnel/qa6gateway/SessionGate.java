package com.tunnel.qa6gateway;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.dto.SessionStatus;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * The Gateway's lightweight session check (the only Redis touch on the tunnel
 * path): one {@code HGETALL session:{id}} confirming the session exists, is
 * {@code ACTIVE}, and has a pod assigned. The Router still does the authoritative
 * {@code session→pod} resolution — this is an early gate, not the security boundary.
 */
@Service
public class SessionGate {

    private static final Logger log = LoggerFactory.getLogger(SessionGate.class);

    /** Outcome of the gate check. */
    public enum Result { VALID, INVALID, REDIS_ERROR }

    private final StringRedisTemplate redis;

    public SessionGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Result validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Result.INVALID;
        }
        try {
            Map<Object, Object> session = redis.opsForHash().entries(RedisKeys.session(sessionId));
            if (session == null || session.isEmpty()) {
                return Result.INVALID; // unknown or expired
            }
            String status = str(session.get(RedisKeys.F_STATUS));
            String podId = str(session.get(RedisKeys.F_POD_ID));
            boolean active = SessionStatus.ACTIVE.name().equals(status);
            boolean hasPod = podId != null && !podId.isBlank();
            return (active && hasPod) ? Result.VALID : Result.INVALID;
        } catch (RuntimeException e) {
            // Redis unreachable / data-access failure → caller decides fail-open vs fail-closed.
            log.warn("redis error validating session: {}", e.toString());
            return Result.REDIS_ERROR;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
