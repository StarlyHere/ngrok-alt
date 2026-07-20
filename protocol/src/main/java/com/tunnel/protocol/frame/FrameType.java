package com.tunnel.protocol.frame;

/**
 * The kind of a mux {@link Frame}. Encoded on the wire as a single unsigned
 * byte (see {@link FrameCodec}).
 *
 * <p>Frames split into two groups:
 * <ul>
 *   <li><b>Stream frames</b> ({@link #OPEN_STREAM}, {@link #DATA},
 *       {@link #CLOSE_STREAM}, {@link #RESET_STREAM}) carry per-request data and
 *       are scoped to a non-zero stream id.</li>
 *   <li><b>Control frames</b> ({@link #REGISTER}, {@link #REGISTER_ACK},
 *       {@link #HEARTBEAT}, {@link #CLOSE_SESSION}, {@link #ERROR}) manage the
 *       connection/session itself and use stream id {@code 0}.</li>
 * </ul>
 */
public enum FrameType {

    // --- stream frames (stream-id > 0) ---
    /** Open a new multiplexed stream. */
    OPEN_STREAM(0x01),
    /** Payload bytes for an open stream. */
    DATA(0x02),
    /** Graceful half/full close of a stream. */
    CLOSE_STREAM(0x03),
    /** Abrupt termination of a stream (error). */
    RESET_STREAM(0x04),

    // --- control frames (stream-id == 0) ---
    /** Client → pod: register/attach a session on this connection. */
    REGISTER(0x10),
    /** Pod → client: acknowledge registration. */
    REGISTER_ACK(0x11),
    /** Liveness ping that refreshes the ownership TTL (PRD-v2.md §4.3). */
    HEARTBEAT(0x12),
    /** Tear down the whole session/connection. */
    CLOSE_SESSION(0x13),
    /** Carry a protocol error code (see {@code ErrorCodes}). */
    ERROR(0x1F);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    /** The single unsigned byte written on the wire for this type. */
    public int code() {
        return code;
    }

    /** @return {@code true} for control frames, which always use stream id 0. */
    public boolean isControl() {
        return code >= 0x10;
    }

    /**
     * Resolve a wire byte back to a {@link FrameType}.
     *
     * @throws IllegalArgumentException if the byte is not a known type
     */
    public static FrameType fromCode(int code) {
        for (FrameType t : values()) {
            if (t.code == (code & 0xFF)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown frame type: 0x" + Integer.toHexString(code & 0xFF));
    }
}
