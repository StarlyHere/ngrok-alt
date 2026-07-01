package com.tunnel.protocol;

/**
 * Shared constants used across modules: header names, well-known ports, and
 * default policy values (BUILD-CHECKLIST.md Part 1; PRD-v2.md §5, §7, §9).
 */
public final class TunnelConstants {

    private TunnelConstants() {
    }

    // --- HTTP / handshake headers ---

    /** Identifies the target session on a routed request (PRD-v2.md §5). */
    public static final String HEADER_SESSION = "X-Tunnel-Session";

    /** Bearer token on the WSS handshake (PRD-v2.md §9.2.2). */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Correlation/trace id propagated Router→Pod→Client→Service (PRD-v2.md §11). */
    public static final String HEADER_TRACE = "X-Tunnel-Trace";

    /** Pod that owns/served a request, surfaced for the inspector/metrics. */
    public static final String HEADER_POD = "X-Tunnel-Pod";

    /** Prefix for bearer tokens in the Authorization header. */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * Extract the bearer token from an {@code Authorization: Bearer <token>}
     * header value, or {@code null} if absent/malformed.
     */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Show only the first 6 chars of a secret (token/session id), masking the rest. */
    public static String redactSecret(String secret) {
        return secret == null || secret.length() <= 6 ? "******" : secret.substring(0, 6) + "…";
    }

    // --- well-known ports ---

    /** Local request inspector served by the client binary (PRD-v2.md §7, §11). */
    public static final int INSPECTOR_PORT = 4040;

    /** Sample developer service port used throughout the docs/demos. */
    public static final int DEFAULT_DEV_SERVICE_PORT = 3000;

    // --- routing / naming ---

    /** Host suffix for session subdomains, e.g. {@code bright-otter-12.tunnel.local}. */
    public static final String TUNNEL_DOMAIN_SUFFIX = ".tunnel.local";

    // --- session / heartbeat policy defaults ---

    /** Session id size in bits (cryptographically random — PRD-v2.md §9.2.1). */
    public static final int SESSION_ID_BITS = 128;

    /** Default ownership TTL refreshed by heartbeats (PRD-v2.md §4.3). */
    public static final long DEFAULT_OWNERSHIP_TTL_MS = 30_000L;

    /** Default client heartbeat interval (well under the TTL above). */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L;

    // --- default quotas (PRD-v2.md §9.2.6) ---

    /** Max concurrent sessions a single owner may hold. */
    public static final int DEFAULT_MAX_SESSIONS_PER_OWNER = 5;

    /** Max concurrent multiplexed streams per session. */
    public static final int DEFAULT_MAX_STREAMS_PER_SESSION = 256;
}
