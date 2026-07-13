package com.tunnel.protocol.dto;

/**
 * Client → Coordinator request to obtain a pod assignment (PRD-v2.md §4 path A,
 * §10). Sent on first connect and again on reconnect.
 *
 * @param ownerId           owner identity (token-bound in Part 4)
 * @param targetPort        the localhost port the client will forward to
 * @param existingSessionId on reconnect, the client's current session id so the
 *                          Coordinator reuses it (stable URL) and only re-picks a
 *                          live pod; {@code null} to create a brand-new session
 * @param pathPatterns      comma-separated Ant-style path patterns to intercept
 *                          (e.g. {@code "/process/**,/ui/graphql/**"}); {@code null}
 *                          or absent means intercept everything (treated as "ALL")
 * @param createIngress     when {@code true} the Coordinator creates a temporary
 *                          Kubernetes Ingress scoped to this session's subdomain
 */
public record AssignmentRequest(
        String ownerId,
        int targetPort,
        String existingSessionId,
        String pathPatterns,
        boolean createIngress) {

    /** Convenience constructor for callers that don't use the new fields (backward compat). */
    public static AssignmentRequest basic(String ownerId, int targetPort, String existingSessionId) {
        return new AssignmentRequest(ownerId, targetPort, existingSessionId, null, false);
    }
}
