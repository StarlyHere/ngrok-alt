package com.tunnel.protocol.frame;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Encodes/decodes {@link Frame}s to and from the mux wire format.
 *
 * <p>Wire layout (big-endian, fixed 9-byte header then payload):
 * <pre>
 *   0        4        5                 9
 *   +--------+--------+-----------------+----------------------+
 *   | stream | type   | length          | payload (length)     |
 *   |  id    | (1 B)  |  (4 B, unsigned) |                      |
 *   | (4 B)  |        |                 |                      |
 *   +--------+--------+-----------------+----------------------+
 * </pre>
 *
 * The frame is intentionally minimal and transport-agnostic: a WebSocket binary
 * message, an SSH channel, or a raw socket can all carry these bytes.
 */
public final class FrameCodec {

    /** Bytes of fixed header preceding every payload: 4 (stream) + 1 (type) + 4 (length). */
    public static final int HEADER_SIZE = 9;

    /**
     * Hard cap on a single frame payload (16 MiB). Guards the decoder against a
     * corrupt/hostile length field allocating unbounded memory.
     */
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private FrameCodec() {
    }

    /** Serialize a frame to a freshly allocated byte array. */
    public static byte[] encode(Frame frame) {
        byte[] payload = frame.payload();
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.putInt(frame.streamId());
        buf.put((byte) frame.type().code());
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    /** Write a frame to a stream. Does not flush. */
    public static void write(OutputStream out, Frame frame) throws IOException {
        out.write(encode(frame));
    }

    /**
     * Decode a single frame from a complete byte array (e.g. one WebSocket
     * binary message).
     *
     * @throws IllegalArgumentException if the buffer is malformed or truncated
     */
    public static Frame decode(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Frame too short: " + bytes.length + " < " + HEADER_SIZE);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int streamId = buf.getInt();
        int typeCode = buf.get() & 0xFF;
        int length = buf.getInt();
        validateLength(length, bytes.length - HEADER_SIZE);
        byte[] payload = new byte[length];
        buf.get(payload);
        return new Frame(streamId, FrameType.fromCode(typeCode), payload);
    }

    /**
     * Read exactly one frame from a (blocking) stream, consuming its bytes.
     *
     * @return the decoded frame, or {@code null} on a clean end-of-stream before
     *         any header byte is read
     * @throws EOFException if the stream ends partway through a frame
     */
    public static Frame read(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int first = in.read();
        if (first == -1) {
            return null; // clean EOF at a frame boundary
        }
        header[0] = (byte) first;
        readFully(in, header, 1, HEADER_SIZE - 1);

        ByteBuffer buf = ByteBuffer.wrap(header);
        int streamId = buf.getInt();
        int typeCode = buf.get() & 0xFF;
        int length = buf.getInt();
        validateLength(length, MAX_PAYLOAD);

        byte[] payload = new byte[length];
        readFully(in, payload, 0, length);
        return new Frame(streamId, FrameType.fromCode(typeCode), payload);
    }

    private static void validateLength(int length, int available) {
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid frame length: " + length);
        }
        if (length > available) {
            throw new IllegalArgumentException(
                    "Truncated frame: declared length " + length + " > available " + available);
        }
    }
    

    private static void readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(dst, off + read, len - read);
            if (n == -1) {
                throw new EOFException("Stream ended " + read + "/" + len + " bytes into frame body");
            }
            read += n;
        }
    }
}
