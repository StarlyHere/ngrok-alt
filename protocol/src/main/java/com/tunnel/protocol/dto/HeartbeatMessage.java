package com.tunnel.protocol.dto;

/**
 * Client → pod liveness ping (carried in a
 * {@link com.tunnel.protocol.frame.FrameType#HEARTBEAT} frame).
 *
 * <p>Receiving this refreshes the {@code session→pod} ownership TTL in Redis —
 * the single mechanism that delivers both heartbeat monitoring and failure
 * detection (PRD-v2.md §4.3).
 *
 * @param sessionId    the session this heartbeat keeps alive
 * @param ownerId      owner identity; only the owner may refresh (§9.2.3)
 * @param sentAtEpochMs client send time, epoch millis (round-trip/latency aid)
 */
public record HeartbeatMessage(
        String sessionId,
        String ownerId,
        long sentAtEpochMs) {
}
