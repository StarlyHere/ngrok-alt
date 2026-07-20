package com.tunnel.control;

import com.tunnel.protocol.TunnelConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinator configuration, bound from {@code control.*}.
 *
 * @param sessionTtlMs         TTL on a freshly created session record (refreshed by
 *                             the pod on register/heartbeat — PRD-v2.md §4.3)
 * @param heartbeatIntervalMs  cadence the client is told to heartbeat at
 * @param loadStrategy         assignment strategy: {@code least-connections}
 *                             (default), {@code round-robin}, or {@code random}
 * @param tokenTtlMs           TTL on an issued bearer token (long-lived, Tier 1)
 * @param maxSessionsPerOwner  quota: concurrent sessions one owner may hold (§9.2.6)
 * @param tunnelUrl            public WebSocket URL returned to clients in AssignmentResponse;
 *                             set via CONTROL_TUNNEL_URL env var; falls back to pod IP when blank
 * @param ingressEnabled       feature flag — enables ingress lifecycle management (default false)
 * @param ingressDomain        base domain for per-session ingress hostnames
 *                             (e.g. {@code space-qa6.sprinklr.com})
 * @param ingressNamespace     Kubernetes namespace where temporary ingresses are created
 */
@ConfigurationProperties(prefix = "control")
public record ControlProperties(
        long sessionTtlMs,
        long heartbeatIntervalMs,
        String loadStrategy,
        long tokenTtlMs,
        int maxSessionsPerOwner,
        String tunnelUrl,
        boolean ingressEnabled,
        String ingressDomain,
        String ingressNamespace) {

    public ControlProperties {
        if (sessionTtlMs <= 0) {
            sessionTtlMs = 15_000;
        }
        if (heartbeatIntervalMs <= 0) {
            heartbeatIntervalMs = TunnelConstants.DEFAULT_HEARTBEAT_INTERVAL_MS;
        }
        if (loadStrategy == null || loadStrategy.isBlank()) {
            loadStrategy = "least-connections";
        }
        if (tokenTtlMs <= 0) {
            tokenTtlMs = 7L * 24 * 60 * 60 * 1000; // 7 days
        }
        if (maxSessionsPerOwner <= 0) {
            maxSessionsPerOwner = TunnelConstants.DEFAULT_MAX_SESSIONS_PER_OWNER;
        }
        if (ingressDomain == null || ingressDomain.isBlank()) {
            ingressDomain = "space-qa6.sprinklr.com";
        }
        if (ingressNamespace == null || ingressNamespace.isBlank()) {
            ingressNamespace = "tunnel";
        }
    }
}
