package com.tunnel.protocol.mux;

import com.tunnel.protocol.frame.Frame;
import java.io.IOException;

/**
 * The outbound sink a {@link MuxConnection} writes frames to. Implemented by the
 * transport binding (a WebSocket session on the pod, a {@code java.net.http}
 * WebSocket on the client) — the only piece that knows how to actually put bytes
 * on the wire.
 *
 * <p>Implementations MUST be safe to call from multiple threads (concurrent
 * streams send frames independently); typically they serialize sends with a lock.
 */
@FunctionalInterface
public interface FrameChannel {

    /** Encode and transmit a single frame. */
    void send(Frame frame) throws IOException;
}
