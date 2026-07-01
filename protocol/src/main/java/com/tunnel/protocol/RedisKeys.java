package com.tunnel.protocol;

/**
 * Canonical Redis key + field names shared by the Coordinator, Pods, and Router
 * (PRD-v2.md §5, §10). Centralizing them here guarantees the three services
 * agree on the schema — the source of truth for ownership, TTL, and load.
 *
 * <p>Layout:
 * <pre>
 *   session:{id}            HASH  {ownerId, subdomain, podId, status, createdAt}   (TTL = ownership)
 *   subdomain:{name}        STR   sessionId                                        (TTL, reverse index)
 *   pod:{id}                HASH  {host, port, conns, lastSeen}                     (TTL = liveness)
 *   owner:{ownerId}:sessions SET  {sessionId, …}                                   (audit / quotas)
 * </pre>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // --- key builders ---

    public static String session(String sessionId) {
        return "session:" + sessionId;
    }

    public static String subdomainIndex(String subdomain) {
        return "subdomain:" + subdomain;
    }

    public static String pod(String podId) {
        return "pod:" + podId;
    }

    public static String ownerSessions(String ownerId) {
        return "owner:" + ownerId + ":sessions";
    }

    /** {@code auth:token:{token}} → ownerId. The bearer-token → identity mapping (§9.2.2). */
    public static String authToken(String token) {
        return "auth:token:" + token;
    }

    /** SCAN match pattern enumerating live pod hashes for assignment. */
    public static final String POD_SCAN_PATTERN = "pod:*";

    // --- session hash fields ---

    public static final String F_OWNER_ID = "ownerId";
    public static final String F_SUBDOMAIN = "subdomain";
    public static final String F_POD_ID = "podId";
    public static final String F_STATUS = "status";
    public static final String F_CREATED_AT = "createdAt";

    // --- pod hash fields ---

    public static final String F_HOST = "host";
    public static final String F_PORT = "port";
    public static final String F_CONNS = "conns";
    public static final String F_LAST_SEEN = "lastSeen";
    /** "true" when this pod serves TLS (wss/https external hops — §9.2.5). */
    public static final String F_SECURE = "secure";
}
