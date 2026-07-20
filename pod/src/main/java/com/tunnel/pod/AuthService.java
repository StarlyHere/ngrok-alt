package com.tunnel.pod;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.TunnelConstants;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Pod-side token validation (PRD-v2.md §9.2.2). The pod is not the auth
 * authority — the Coordinator mints tokens — but it independently verifies the
 * bearer token presented on the WebSocket handshake against the same Redis
 * {@code auth:token:{token} → ownerId} mapping, so an unauthenticated client
 * cannot open a tunnel.
 */
@Service
public class AuthService {

    private final StringRedisTemplate redis;

    public AuthService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Resolve a bearer token to its owner identity, if valid. */
    public Optional<String> ownerForToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.authToken(token)));
    }

    /** Resolve the owner from an {@code Authorization: Bearer …} header value. */
    public Optional<String> ownerForAuthHeader(String authorizationHeader) {
        return ownerForToken(TunnelConstants.bearerToken(authorizationHeader));
    }
}
