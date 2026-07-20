package com.tunnel.protocol.frame;

import java.util.Arrays;
import java.util.Objects;

/**
 * One unit on the mux wire (BUILD-CHECKLIST.md Part 1).
 *
 * <p>Logical shape: {@code (stream-id, type, length, payload)}. The {@code length}
 * is not stored here — it is always {@code payload.length} and is written by
 * {@link FrameCodec} during encoding.
 *
 * @param streamId per-connection stream id; {@code 0} is reserved for control
 *                 frames ({@link FrameType#isControl()})
 * @param type     the {@link FrameType}
 * @param payload  frame body; never {@code null} (use an empty array)
 */
public record Frame(int streamId, FrameType type, byte[] payload) {

    /** Control frames always live on stream 0. */
    public static final int CONTROL_STREAM_ID = 0;

    public Frame {
        Objects.requireNonNull(type, "type");
        if (payload == null) {
            payload = new byte[0];
        }
        if (type.isControl() && streamId != CONTROL_STREAM_ID) {
            throw new IllegalArgumentException(
                    "Control frame " + type + " must use stream id 0, got " + streamId);
        }
        if (!type.isControl() && streamId <= CONTROL_STREAM_ID) {
            throw new IllegalArgumentException(
                    "Stream frame " + type + " must use a stream id > 0, got " + streamId);
        }
    }

    /** A control frame on stream 0 with no payload. */
    public static Frame control(FrameType type) {
        return new Frame(CONTROL_STREAM_ID, type, new byte[0]);
    }

    /** A control frame on stream 0 carrying the given payload. */
    public static Frame control(FrameType type, byte[] payload) {
        return new Frame(CONTROL_STREAM_ID, type, payload);
    }

    /** A {@link FrameType#DATA} frame for the given stream. */
    public static Frame data(int streamId, byte[] payload) {
        return new Frame(streamId, FrameType.DATA, payload);
    }

    /** A {@link FrameType#OPEN_STREAM} frame for the given stream. */
    public static Frame open(int streamId) {
        return new Frame(streamId, FrameType.OPEN_STREAM, new byte[0]);
    }

    /** A {@link FrameType#CLOSE_STREAM} frame for the given stream. */
    public static Frame close(int streamId) {
        return new Frame(streamId, FrameType.CLOSE_STREAM, new byte[0]);
    }

    /** A {@link FrameType#RESET_STREAM} frame for the given stream. */
    public static Frame reset(int streamId) {
        return new Frame(streamId, FrameType.RESET_STREAM, new byte[0]);
    }

    /** Payload length; equals what {@link FrameCodec} writes as the length field. */
    public int length() {
        return payload.length;
    }

    // records' default equals/hashCode/toString don't handle byte[] well.

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame other)) {
            return false;
        }
        return streamId == other.streamId
                && type == other.type
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(streamId, type) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Frame[streamId=" + streamId + ", type=" + type + ", length=" + payload.length + "]";
    }
}
