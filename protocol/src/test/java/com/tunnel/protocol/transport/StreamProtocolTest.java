package com.tunnel.protocol.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PushbackInputStream;
import org.junit.jupiter.api.Test;

class StreamProtocolTest {
    @Test
    void identifiesTcpMarker() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamProtocol.writeTcpMarker(output);
        assertTrue(StreamProtocol.isTcp(new PushbackInputStream(
                new ByteArrayInputStream(output.toByteArray()), 4)));
    }

    @Test
    void preservesExistingHttpPrefix() throws Exception {
        byte[] original = new byte[]{0, 3, 'G', 'E', 'T'};
        PushbackInputStream input = new PushbackInputStream(new ByteArrayInputStream(original), 4);
        assertFalse(StreamProtocol.isTcp(input));
        assertArrayEquals(original, input.readAllBytes());
    }
}
