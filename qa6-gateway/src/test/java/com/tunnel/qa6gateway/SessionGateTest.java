package com.tunnel.qa6gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class SessionGateTest {

    @SuppressWarnings("unchecked")
    private SessionGate gateReturning(Object entriesResultOrThrow) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        if (entriesResultOrThrow instanceof RuntimeException ex) {
            when(hash.entries(any())).thenThrow(ex);
        } else {
            when(hash.entries(any())).thenReturn((Map<Object, Object>) entriesResultOrThrow);
        }
        return new SessionGate(redis);
    }

    @Test
    void activeSessionWithPodIsValid() {
        SessionGate gate = gateReturning(Map.of("status", "ACTIVE", "podId", "pod-1"));
        assertEquals(SessionGate.Result.VALID, gate.validate("abc123").result());
    }

    @Test
    void activeSessionReturnsStoredPathPatterns() {
        SessionGate gate = gateReturning(Map.of("status", "ACTIVE", "podId", "pod-1",
                "pathPatterns", "/api/**,/graphql"));
        SessionGate.ValidationResult v = gate.validate("abc123");
        assertEquals(SessionGate.Result.VALID, v.result());
        assertEquals("/api/**,/graphql", v.pathPatterns());
    }

    @Test
    void activeSessionWithNoPatternsReturnsNullPathPatterns() {
        SessionGate gate = gateReturning(Map.of("status", "ACTIVE", "podId", "pod-1"));
        SessionGate.ValidationResult v = gate.validate("abc123");
        assertEquals(SessionGate.Result.VALID, v.result());
        assertNull(v.pathPatterns());
    }

    @Test
    void unknownOrExpiredSessionIsInvalid() {
        SessionGate gate = gateReturning(Map.of());
        assertEquals(SessionGate.Result.INVALID, gate.validate("abc123").result());
    }

    @Test
    void pendingStatusIsInvalid() {
        SessionGate gate = gateReturning(Map.of("status", "PENDING", "podId", "pod-1"));
        assertEquals(SessionGate.Result.INVALID, gate.validate("abc123").result());
    }

    @Test
    void activeButNoPodIsInvalid() {
        SessionGate gate = gateReturning(Map.of("status", "ACTIVE"));
        assertEquals(SessionGate.Result.INVALID, gate.validate("abc123").result());
    }

    @Test
    void blankSessionIdIsInvalid() {
        SessionGate gate = gateReturning(Map.of());
        assertEquals(SessionGate.Result.INVALID, gate.validate("  ").result());
    }

    @Test
    void redisFailureIsRedisError() {
        SessionGate gate = gateReturning(new RuntimeException("connection refused"));
        assertEquals(SessionGate.Result.REDIS_ERROR, gate.validate("abc123").result());
    }
}
