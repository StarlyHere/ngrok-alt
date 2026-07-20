package com.tunnel.pod;

import com.tunnel.protocol.frame.FrameCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Server endpoint for the developer's outbound tunnel connection over WebSocket
 * (BUILD-CHECKLIST.md Part 2). Each WebSocket session is wrapped in a
 * {@link PodConnection}; inbound binary messages are decoded frames fed to that
 * connection's mux.
 */
@Component
public class TunnelWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TunnelWebSocketHandler.class);

    private final ConnectionRegistry registry;
    private final PodProperties props;
    private final PodRedisService redis;
    private final ConcurrentHashMap<String, PodConnection> bySocketId = new ConcurrentHashMap<>();

    public TunnelWebSocketHandler(ConnectionRegistry registry, PodProperties props, PodRedisService redis) {
        this.registry = registry;
        this.props = props;
        this.redis = redis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String ownerId = (String) session.getAttributes().get(AuthHandshakeInterceptor.ATTR_OWNER);
        PodConnection conn = new PodConnection(
            frame -> {
                byte[] bytes = FrameCodec.encode(frame);
                synchronized (session) {
                    session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
                }
            },
            () -> {
                try {
                    if (session.isOpen()) session.close();
                } catch (IOException ignored) {}
            },
            registry, props, redis, ownerId);
        bySocketId.put(session.getId(), conn);
        log.info("client connected: socket {} (pod {}, owner '{}')", session.getId(), props.id(), ownerId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        PodConnection conn = bySocketId.get(session.getId());
        if (conn == null) {
            return;
        }
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);
        conn.onBinary(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PodConnection conn = bySocketId.remove(session.getId());
        if (conn != null) {
            conn.close();
        }
        log.info("client disconnected: socket {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("transport error on socket {}: {}", session.getId(), exception.toString());
    }
}
