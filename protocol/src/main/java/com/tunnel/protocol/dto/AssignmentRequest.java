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
 */
public record AssignmentRequest(
        String ownerId,
        int targetPort,
        String existingSessionId) {
}
