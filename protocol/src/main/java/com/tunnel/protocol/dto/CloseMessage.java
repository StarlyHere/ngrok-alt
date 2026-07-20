package com.tunnel.protocol.dto;

/**
 * Tear-down control message for a session (carried in a
 * {@link com.tunnel.protocol.frame.FrameType#CLOSE_SESSION} frame).
 *
 * <p>May flow client → pod (developer ran {@code tunnel} shutdown) or pod →
 * client (graceful drain on SIGTERM, asking the client to reconnect elsewhere —
 * PRD-v2.md §10). Only the owner may close a session (§9.2.3).
 *
 * @param sessionId the session being closed
 * @param ownerId   owner identity; cross-checked before honouring the close
 * @param reason    a {@code ErrorCodes}/diagnostic string, e.g. {@code "drain"}
 */
public record CloseMessage(
        String sessionId,
        String ownerId,
        String reason) {
}
