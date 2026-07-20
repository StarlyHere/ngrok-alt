package com.tunnel.pod;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the WebSocket handshake (PRD-v2.md §9.2.2): the client must
 * present a valid {@code Authorization: Bearer} token, or the handshake is
 * rejected with {@code 401} before any tunnel is established. The resolved owner
 * is stashed in the session attributes for the owner check at register time.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    /** Session-attribute key holding the authenticated owner id. */
    static final String ATTR_OWNER = "tunnel.ownerId";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    private final AuthService auth;

    public AuthHandshakeInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        Optional<String> owner = auth.ownerForAuthHeader(authorization);
        if (owner.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.warn("rejected unauthenticated tunnel handshake from {}", request.getRemoteAddress());
            return false;
        }
        attributes.put(ATTR_OWNER, owner.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
