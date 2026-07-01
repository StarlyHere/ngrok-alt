package com.tunnel.protocol.frame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    @Test
    void encodeDecodeRoundTripsDataFrame() {
        byte[] payload = "hello tunnel".getBytes(StandardCharsets.UTF_8);
        Frame original = Frame.data(7, payload);

        Frame decoded = FrameCodec.decode(FrameCodec.encode(original));

        assertEquals(7, decoded.streamId());
        assertEquals(FrameType.DATA, decoded.type());
        assertArrayEquals(payload, decoded.payload());
        assertEquals(original, decoded);
    }

    @Test
    void encodeWritesHeaderPlusPayloadLength() {
        Frame frame = Frame.data(1, new byte[] {1, 2, 3});
        byte[] bytes = FrameCodec.encode(frame);
        assertEquals(FrameCodec.HEADER_SIZE + 3, bytes.length);
    }

    @Test
    void controlFrameUsesStreamZero() {
        Frame ping = Frame.control(FrameType.HEARTBEAT);
        assertEquals(Frame.CONTROL_STREAM_ID, ping.streamId());
        Frame decoded = FrameCodec.decode(FrameCodec.encode(ping));
        assertEquals(FrameType.HEARTBEAT, decoded.type());
        assertEquals(0, decoded.streamId());
    }

    @Test
    void controlFrameRejectsNonZeroStream() {
        assertThrows(IllegalArgumentException.class,
                () -> new Frame(5, FrameType.HEARTBEAT, new byte[0]));
    }

    @Test
    void streamFrameRejectsStreamZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new Frame(0, FrameType.DATA, new byte[0]));
    }

    @Test
    void readBackToBackFramesFromStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec.write(out, Frame.data(1, "first".getBytes(StandardCharsets.UTF_8)));
        FrameCodec.write(out, Frame.control(FrameType.HEARTBEAT));
        FrameCodec.write(out, Frame.data(2, "third".getBytes(StandardCharsets.UTF_8)));

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Frame a = FrameCodec.read(in);
        Frame b = FrameCodec.read(in);
        Frame c = FrameCodec.read(in);

        assertEquals("first", new String(a.payload(), StandardCharsets.UTF_8));
        assertEquals(FrameType.HEARTBEAT, b.type());
        assertEquals("third", new String(c.payload(), StandardCharsets.UTF_8));
        assertNull(FrameCodec.read(in), "clean EOF at frame boundary returns null");
    }

    @Test
    void decodeRejectsTruncatedBuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(new byte[] {0, 0, 0, 1}));
    }

    @Test
    void fromCodeRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> FrameType.fromCode(0x99));
    }
}
