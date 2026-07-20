package com.tunnel.protocol.transport;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameType;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * A single long-lived, bidirectional, multiplexed connection between a tunnel
 * client and a tunnel pod (PRD-v2.md §4 principle #1).
 *
 * <p>The connection is opened <em>outbound</em> by the client (NAT/firewall
 * friendly), then reused in reverse by the pod to push QA6 requests down. Each
 * concurrent request is a {@link TunnelStream} multiplexed over this one socket.
 *
 * <p>Streams flow in both directions:
 * <ul>
 *   <li>{@link #openStream()} — locally initiated (e.g. the pod opening a stream
 *       to deliver an inbound request to the client).</li>
 *   <li>{@link #acceptStream()} — blocks until the remote peer opens a stream,
 *       returning it for handling.</li>
 * </ul>
 */
public interface TunnelConnection extends Closeable {

    /** Open a new locally initiated multiplexed stream. */
    TunnelStream openStream() throws IOException;

    /**
     * Block until the remote peer opens a stream, then return it.
     *
     * @return the next remotely initiated stream, or {@code null} if the
     *         connection has been closed
     */
    TunnelStream acceptStream() throws IOException;

    /** @return {@code true} while the underlying connection is usable. */
    boolean isOpen();

    /** Close the connection and all of its streams. */
    @Override
    void close();

    /** Send a control frame (REGISTER / HEARTBEAT / CLOSE_SESSION / …). */
    void sendControl(FrameType type, byte[] payload) throws IOException;

    /**
     * Register a handler for inbound control frames. Transports that buffer
     * frames until the caller is ready override this; others leave the no-op.
     */
    default void setControlHandler(Consumer<Frame> handler) {}
}
