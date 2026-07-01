package com.tunnel.client.transport;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WebSocket.Listener} that reassembles (possibly fragmented) binary
 * messages into whole encoded frames and feeds them to a
 * {@link WsTunnelConnection}. Demand is managed explicitly via
 * {@link WebSocket#request(long)} so backpressure is honoured.
 */
class WsFrameListener implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(WsFrameListener.class);

    private final WsTunnelConnection connection;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    WsFrameListener(WsTunnelConnection connection) {
        this.connection = connection;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        buffer.writeBytes(chunk);
        if (last) {
            byte[] message = buffer.toByteArray();
            buffer.reset();
            try {
                connection.onMessage(message);
            } catch (RuntimeException e) {
                log.warn("failed handling inbound frame: {}", e.toString());
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.info("websocket closed by peer: {} {}", statusCode, reason);
        connection.transportClosed();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.warn("websocket error: {}", error.toString());
        connection.transportClosed();
    }
}
