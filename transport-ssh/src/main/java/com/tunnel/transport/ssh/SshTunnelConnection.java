package com.tunnel.transport.ssh;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.mux.MuxConnection;
import com.tunnel.protocol.transport.TunnelConnection;
import com.tunnel.protocol.transport.TunnelStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SSH-transport realization of {@link TunnelConnection}. Wraps a
 * {@link FramedByteTransport} and exposes {@link #setControlHandler} so the
 * caller can register the REGISTER_ACK / CLOSE_SESSION handler after the
 * connection is opened (matching the WebSocket provider contract).
 */
final class SshTunnelConnection implements TunnelConnection {

    private final MuxConnection mux;
    private final FramedByteTransport transport;
    final AtomicReference<Consumer<Frame>> controlRef;

    SshTunnelConnection(MuxConnection mux, FramedByteTransport transport,
                        AtomicReference<Consumer<Frame>> controlRef) {
        this.mux = mux;
        this.transport = transport;
        this.controlRef = controlRef;
    }

    @Override
    public void setControlHandler(Consumer<Frame> handler) {
        controlRef.set(handler);
    }

    @Override
    public void sendControl(FrameType type, byte[] payload) throws IOException {
        mux.sendControl(type, payload);
    }

    @Override
    public TunnelStream openStream() throws IOException {
        return mux.openStream();
    }

    @Override
    public TunnelStream acceptStream() {
        return mux.acceptStream();
    }

    @Override
    public boolean isOpen() {
        return mux.isOpen();
    }

    @Override
    public void close() {
        transport.close();
    }
}
