package com.tunnel.pod;

import com.tunnel.protocol.TunnelConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pod configuration (PRD-v2.md §5, §10). Bound from {@code pod.*}.
 *
 * @param id                   this pod's identity (the routing target id)
 * @param host                 host clients/router reach this pod at
 * @param port                 port (serves both the WS tunnel and the data plane)
 * @param heartbeatIntervalMs  cadence the pod asks clients to heartbeat at, and
 *                             the cadence of its own Redis self-heartbeat
 * @param podTtlMs             TTL on this pod's Redis liveness key
 * @param sessionTtlMs         TTL the pod (re)applies to a session on register/heartbeat
 * @param maxStreamsPerSession per-session concurrent stream cap (backpressure)
 * @param sshPort              port the SSH tunnel server listens on
 * @param tcpPort              cluster-internal raw TCP bridge port
 */
@ConfigurationProperties(prefix = "pod")
public record PodProperties(
        String id,
        String host,
        int port,
        boolean secure,
        long heartbeatIntervalMs,
        long podTtlMs,
        long sessionTtlMs,
        int maxStreamsPerSession,
        int sshPort,
        int tcpPort) {

    public PodProperties {
        if (id == null || id.isBlank()) {
            id = "pod-local";
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (port <= 0) {
            port = 8080;
        }
        if (heartbeatIntervalMs <= 0) {
            heartbeatIntervalMs = TunnelConstants.DEFAULT_HEARTBEAT_INTERVAL_MS;
        }
        if (podTtlMs <= 0) {
            podTtlMs = 15_000;
        }
        if (sessionTtlMs <= 0) {
            sessionTtlMs = 15_000;
        }
        if (maxStreamsPerSession <= 0) {
            maxStreamsPerSession = TunnelConstants.DEFAULT_MAX_STREAMS_PER_SESSION;
        }
        if (sshPort <= 0) {
            sshPort = 2222;
        }
        if (tcpPort <= 0) {
            tcpPort = 8182;
        }
    }
}
