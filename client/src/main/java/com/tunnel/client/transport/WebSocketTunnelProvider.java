package com.tunnel.client.transport;

import com.tunnel.protocol.transport.TunnelProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket implementation of the {@link TunnelProvider} seam (PRD-v2.md §8) —
 * the default/primary transport. Opens one outbound WSS connection to a pod and
 * returns a {@link WsTunnelConnection} that multiplexes streams over it.
 */
public class WebSocketTunnelProvider implements TunnelProvider {

    private final HttpClient httpClient;
    private final Duration handshakeTimeout;

    public WebSocketTunnelProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(10));
    }

    public WebSocketTunnelProvider(HttpClient httpClient, Duration handshakeTimeout) {
        this.httpClient = httpClient;
        this.handshakeTimeout = handshakeTimeout;
    }

    @Override
    public String name() {
        return "ws";
    }

    @Override
    public WsTunnelConnection open(URI endpoint, Map<String, String> headers) throws Exception {
        WsTunnelConnection connection = new WsTunnelConnection();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(handshakeTimeout);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        WebSocket webSocket = builder
                .buildAsync(endpoint, new WsFrameListener(connection))
                .get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        connection.attach(webSocket);
        return connection;
    }
}
