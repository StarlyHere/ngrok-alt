package com.tunnel.protocol.dto;

/**
 * Pod → client response to a {@link RegisterMessage} (carried in a
 * {@link com.tunnel.protocol.frame.FrameType#REGISTER_ACK} frame).
 *
 * @param accepted    whether registration succeeded
 * @param session     the resolved session (with assigned pod) when accepted
 * @param errorCode   an {@code ErrorCodes} value when rejected; else {@code null}
 * @param podId       the pod that accepted the registration
 * @param heartbeatIntervalMs interval the client should heartbeat at to keep the
 *                    ownership TTL fresh (PRD-v2.md §4.3)
 */
public record RegisterAck(
        boolean accepted,
        Session session,
        String errorCode,
        String podId,
        long heartbeatIntervalMs) {

    public static RegisterAck ok(Session session, String podId, long heartbeatIntervalMs) {
        return new RegisterAck(true, session, null, podId, heartbeatIntervalMs);
    }

    public static RegisterAck rejected(String errorCode) {
        return new RegisterAck(false, null, errorCode, null, 0L);
    }
}
