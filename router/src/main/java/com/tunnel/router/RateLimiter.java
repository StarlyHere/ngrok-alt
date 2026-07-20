package com.tunnel.router;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A simple per-session, fixed-window request-rate cap at the Router (§9.2.6).
 * Bounds how fast any one tunnel can be driven, mitigating resource-abuse / DoS.
 * In-memory and per-router-instance — adequate for the POC (a shared limiter
 * would use Redis; noted for QA6).
 */
@Component
public class RateLimiter {

    private record Window(long second, AtomicInteger count) {
    }

    private final int permitsPerSecond;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${router.rate-limit-per-sec:50}") int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    /**
     * @param key      the session id (or any rate key)
     * @param epochMs  current time in ms (passed in to keep this testable)
     * @return true if the request is within the cap; false if it should be throttled
     */
    public boolean allow(String key, long epochMs) {
        if (permitsPerSecond <= 0) {
            return true; // disabled
        }
        long second = epochMs / 1000;
        Window w = windows.compute(key, (k, existing) ->
                (existing == null || existing.second() != second)
                        ? new Window(second, new AtomicInteger(0))
                        : existing);
        return w.count().incrementAndGet() <= permitsPerSecond;
    }
}
