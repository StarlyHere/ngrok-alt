package com.tunnel.protocol.dto;

/**
 * Coordinator → client pod assignment (PRD-v2.md §4 path A). Tells the client
 * which pod to open its outbound tunnel to, and the stable session identity.
 *
 * @param sessionId           assigned/reused session id
 * @param subdomain           friendly host label (e.g. {@code bright-otter-12})
 * @param podId               the assigned pod's id
 * @param podWsUrl            the pod WebSocket endpoint to connect to
 * @param heartbeatIntervalMs cadence the client should heartbeat at
 * @param errorCode           an {@code ErrorCodes} value when rejected, else null
 */
public record AssignmentResponse(
        String sessionId,
        String subdomain,
        String podId,
        String podWsUrl,
        long heartbeatIntervalMs,
        String errorCode) {

    public static AssignmentResponse ok(String sessionId, String subdomain, String podId,
                                        String podWsUrl, long heartbeatIntervalMs) {
        return new AssignmentResponse(sessionId, subdomain, podId, podWsUrl, heartbeatIntervalMs, null);
    }

    public static AssignmentResponse rejected(String errorCode) {
        return new AssignmentResponse(null, null, null, null, 0L, errorCode);
    }

    public boolean accepted() {
        return errorCode == null;
    }
}
