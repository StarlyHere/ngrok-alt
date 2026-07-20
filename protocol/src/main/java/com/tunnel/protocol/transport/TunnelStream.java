package com.tunnel.protocol.transport;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A single multiplexed stream over a {@link TunnelConnection}, carrying one
 * logical request/response exchange through the tunnel.
 *
 * <p>Each stream has a connection-unique {@link #id()} (see the mux framing in
 * {@code com.tunnel.protocol.frame}). Reads and writes on a stream are
 * independent of other concurrent streams on the same connection.
 */
public interface TunnelStream extends Closeable {

    /** Connection-unique stream identifier (matches the frame stream-id). */
    int id();

    /** Bytes arriving from the peer on this stream. */
    InputStream input();

    /** Bytes written to the peer on this stream. */
    OutputStream output();

    /** Close just this stream, leaving the parent connection open. */
    @Override
    void close();
}
