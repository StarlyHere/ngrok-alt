package com.tunnel.client.transport;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameCodec;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.mux.MuxConnection;
import com.tunnel.protocol.transport.TunnelConnection;
import com.tunnel.protocol.transport.TunnelStream;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A {@link TunnelConnection} backed by a JDK {@link WebSocket} and the shared
 * {@link MuxConnection}. This is the client-side realization of the WebSocket
 * {@code TunnelProvider} transport (PRD-v2.md §8).
 *
 * <p>The client is the stream <em>acceptor</em> (the pod opens streams to deliver
 * inbound requests), so its mux uses even stream ids. Control frames
 * (REGISTER_ACK / CLOSE_SESSION / ERROR) are surfaced via a settable handler.
 */
public class WsTunnelConnection implements TunnelConnection {

    private final Object sendLock = new Object();
    private final MuxConnection mux;
    private volatile WebSocket webSocket;
    private volatile Consumer<Frame> controlHandler = f -> { };
    private volatile Runnable onTransportClosed = () -> { };

    public WsTunnelConnection() {
        // localInitiator=false: client accepts streams, never opens them.
        this.mux = new MuxConnection(this::sendFrame, false, f -> controlHandler.accept(f));
    }

    void attach(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    /** Set the handler for inbound control frames (REGISTER_ACK, CLOSE_SESSION, …). */
    public void setControlHandler(Consumer<Frame> handler) {
        this.controlHandler = handler == null ? f -> { } : handler;
    }

    /** Set a callback fired when the underlying transport closes or errors. */
    public void setOnTransportClosed(Runnable callback) {
        this.onTransportClosed = callback == null ? () -> { } : callback;
    }

    /** Feed one fully-reassembled binary WebSocket message (an encoded frame). */
    void onMessage(byte[] frameBytes) {
        mux.onFrame(FrameCodec.decode(frameBytes));
    }

    void transportClosed() {
        mux.close();
        onTransportClosed.run();
    }

    private void sendFrame(Frame frame) throws IOException {
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new IOException("websocket not connected");
        }
        byte[] bytes = FrameCodec.encode(frame);
        // sendBinary must not be re-invoked before the previous send completes;
        // serialize sends and wait for each to finish.
        synchronized (sendLock) {
            try {
                ws.sendBinary(ByteBuffer.wrap(bytes), true).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted sending frame", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("failed sending frame", e);
            }
        }
    }

    /** Send an application control frame (REGISTER, HEARTBEAT, CLOSE_SESSION). */
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
        WebSocket ws = webSocket;
        return mux.isOpen() && ws != null && !ws.isOutputClosed();
    }

    @Override
    public void close() {
        mux.close();
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").orTimeout(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }
}
