package com.tunnel.pod;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the pod's Redis liveness lifecycle (PRD-v2.md §10): register on startup,
 * refresh the TTL on a schedule (so a crashed pod's key simply expires and it
 * leaves the assignment pool), and deregister on graceful shutdown.
 */
@Component
public class PodLifecycle {

    private final PodRedisService redis;
    private final ConnectionRegistry registry;

    public PodLifecycle(PodRedisService redis, ConnectionRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        redis.registerPod();
    }

    @Scheduled(fixedRateString = "${pod.heartbeat-interval-ms:10000}")
    public void selfHeartbeat() {
        redis.heartbeatPod();
    }

    @PreDestroy
    public void onShutdown() {
        // Leave the assignment pool first (no new sessions), then signal every
        // connected client to reconnect elsewhere — graceful drain (PRD-v2.md §10).
        redis.deregisterPod();
        registry.all().forEach(PodConnection::drain);
    }
}
