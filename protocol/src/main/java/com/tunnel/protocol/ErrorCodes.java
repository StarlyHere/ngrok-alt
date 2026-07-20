package com.tunnel.protocol;

/**
 * Stable, machine-readable error codes exchanged across hops and returned to
 * QA6 traffic (PRD-v2.md §4.3, §9). String constants keep them transport- and
 * language-neutral (the Go client escape hatch reuses the same strings).
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    /** No live session→pod ownership for the requested session (TTL expired or unknown). */
    public static final String TUNNEL_NOT_FOUND = "tunnel-not-found";

    /** Connect/register without a valid auth token (PRD-v2.md §9.2.2). */
    public static final String UNAUTHORIZED = "unauthorized";

    /** Caller is not the owner of the session it tried to heartbeat/close (§9.2.3). */
    public static final String OWNER_MISMATCH = "owner-mismatch";

    /** Forward target is not the allowlisted localhost port (SSRF guard, §9.2.4). */
    public static final String TARGET_NOT_ALLOWED = "target-not-allowed";

    /** A quota was exceeded — sessions/owner, streams/session, or request rate (§9.2.6). */
    public static final String QUOTA_EXCEEDED = "quota-exceeded";

    /** No pod available to accept a new session (assignment pool empty). */
    public static final String NO_POD_AVAILABLE = "no-pod-available";

    /** Generic malformed/invalid protocol message. */
    public static final String BAD_REQUEST = "bad-request";

    /** Unexpected server-side failure. */
    public static final String INTERNAL_ERROR = "internal-error";
}
