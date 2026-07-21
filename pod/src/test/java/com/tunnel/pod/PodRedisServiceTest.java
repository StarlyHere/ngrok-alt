package com.tunnel.pod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tunnel.protocol.RedisKeys;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class PodRedisServiceTest {

    @Test
    void heartbeatRecreatesRoutableFieldsWithoutResettingConnections() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);

        PodProperties properties = new PodProperties(
                "pod-1", "10.0.0.7", 8080, false,
                3_000, 6_000, 15_000, 256, 2222, 8182);
        new PodRedisService(redis, properties).heartbeatPod();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(eq(RedisKeys.pod("pod-1")), fields.capture());
        assertEquals("10.0.0.7", fields.getValue().get(RedisKeys.F_HOST));
        assertEquals("8080", fields.getValue().get(RedisKeys.F_PORT));
        assertEquals("false", fields.getValue().get(RedisKeys.F_SECURE));
        assertFalse(fields.getValue().get(RedisKeys.F_LAST_SEEN).toString().isBlank());
        assertFalse(fields.getValue().containsKey(RedisKeys.F_CONNS));

        verify(hashes).putIfAbsent(
                RedisKeys.pod("pod-1"), RedisKeys.F_CONNS, "0");
        verify(redis).expire(RedisKeys.pod("pod-1"), Duration.ofMillis(6_000));
    }
}
