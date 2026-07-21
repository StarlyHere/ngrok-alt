package com.tunnel.protocol.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;

/** Identifies raw TCP streams without changing the existing HTTP stream encoding. */
public final class StreamProtocol {

    private static final byte[] TCP_MAGIC = new byte[]{'T', 'C', 'P', '1'};

    private StreamProtocol() {}

    public static void writeTcpMarker(OutputStream output) throws IOException {
        output.write(TCP_MAGIC);
        output.flush();
    }

    public static boolean isTcp(PushbackInputStream input) throws IOException {
        byte[] prefix = input.readNBytes(TCP_MAGIC.length);
        if (Arrays.equals(prefix, TCP_MAGIC)) {
            return true;
        }
        input.unread(prefix);
        return false;
    }
}
