package com.tunnel.pod;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Registers pod-level Micrometer gauges (PRD-v2.md §11). Surfaces the live tunnel
 * connection count this pod is holding, scraped at {@code /actuator/prometheus}.
 */
@Component
public class PodMetrics {

    public PodMetrics(MeterRegistry registry, ConnectionRegistry connections, PodProperties props) {
        Gauge.builder("tunnel_pod_connections", connections, ConnectionRegistry::activeSessions)
                .description("Active tunnel client connections held by this pod")
                .tag("pod", props.id())
                .register(registry);
    }
}
