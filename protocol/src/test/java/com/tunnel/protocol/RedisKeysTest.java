package com.tunnel.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RedisKeysTest {

    @Test
    void podIdRoundTripsThroughKey() {
        String key = RedisKeys.pod("pod-123");
        assertEquals("pod-123", RedisKeys.podIdFromKey(key));
    }

    @Test
    void rejectsNonPodKey() {
        assertThrows(IllegalArgumentException.class,
                () -> RedisKeys.podIdFromKey(RedisKeys.session("session-123")));
    }
}
