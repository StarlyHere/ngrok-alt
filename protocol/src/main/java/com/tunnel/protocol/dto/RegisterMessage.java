package com.tunnel.protocol.dto;

/**
 * Client → pod control message sent over a freshly opened connection to attach
 * a session to it (carried in a {@link com.tunnel.protocol.frame.FrameType#REGISTER}
 * frame payload).
 *
 * @param sessionId    the session being registered
 * @param ownerId      owner identity (cross-checked against the auth token)
 * @param targetPort   localhost port the client forwards to (e.g. 3000); the
 *                     allowlist target (PRD-v2.md §9.2.4)
 * @param transport    transport name in use, e.g. {@code "ws"} (§8)
 * @param clientVersion client binary version, for diagnostics
 */
public record RegisterMessage(
        String sessionId,
        String ownerId,
        int targetPort,
        String transport,
        String clientVersion) {
}
