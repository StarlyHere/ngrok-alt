package com.tunnel.control;

import com.tunnel.protocol.RedisKeys;
import com.tunnel.protocol.TunnelConstants;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token issuance + validation for the Coordinator (PRD-v2.md §9.2.2). The
 * Coordinator is the auth authority: {@code tunnel login} mints a long-lived
 * bearer token bound to an owner identity, stored in Redis as
 * {@code auth:token:{token} → ownerId}. Pods validate the same token on connect.
 *
 * <p>The token is a 128-bit secret — never logged in full.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration tokenTtl;

    public AuthService(StringRedisTemplate redis, ControlProperties props) {
        this.redis = redis;
        this.tokenTtl = Duration.ofMillis(props.tokenTtlMs());
    }

    /** Mint a fresh bearer token for an owner and persist the mapping. */
    public String issueToken(String ownerId) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        redis.opsForValue().set(RedisKeys.authToken(token), ownerId, tokenTtl);
        return token;
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
