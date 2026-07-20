package com.tunnel.pod;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the {@code /tunnel} WebSocket endpoint (BUILD-CHECKLIST.md Part 2).
 * The handler mapping takes precedence over MVC, so the forwarding controller's
 * catch-all does not shadow the handshake.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(PodProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    /** Cap on a single binary message; comfortably above one mux frame. */
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private final TunnelWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;

    public WebSocketConfig(TunnelWebSocketHandler handler, AuthHandshakeInterceptor authInterceptor) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/tunnel")
                .addInterceptors(authInterceptor) // reject unauthenticated handshakes (§9.2.2)
                .setAllowedOriginPatterns("*");
    }

    /** Raise the server-side WebSocket buffer sizes for larger payloads. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
