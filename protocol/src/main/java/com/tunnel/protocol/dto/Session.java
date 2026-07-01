package com.tunnel.protocol.dto;

/**
 * The session model — the central record the whole system routes on
 * (PRD-v2.md §5, §9.2).
 *
 * <p>This is the source-of-truth shape stored in Redis as {@code session:{id}}
 * and returned by the Coordinator to the client on connect.
 *
 * @param sessionId  cryptographically random 128-bit id, hex-encoded; a bearer
 *                   capability, so treat as a secret in logs (§9.2.1)
 * @param subdomain  human-friendly host label, e.g. {@code bright-otter-12}
 * @param ownerId    identity that owns the session (from the auth token); the
 *                   security boundary for heartbeat/refresh/close (§9.2.3)
 * @param podId      the pod currently holding this session's socket (path B
 *                   sticky routing target); {@code null} until assigned
 * @param status     current {@link SessionStatus}
 * @param createdAtEpochMs creation time, epoch milliseconds
 */
public record Session(
        String sessionId,
        String subdomain,
        String ownerId,
        String podId,
        SessionStatus status,
        long createdAtEpochMs) {

    /** Redact the session id for log lines — it is a bearer secret. */
    public String redactedId() {
        return redact(sessionId);
    }

    /** Show only the first 6 chars of a secret id, masking the rest. */
    public static String redact(String secret) {
        if (secret == null || secret.length() <= 6) {
            return "******";
        }
        return secret.substring(0, 6) + "…";
    }

    /** Copy with a new pod assignment + ACTIVE status (used on (re)register). */
    public Session withPod(String newPodId) {
        return new Session(sessionId, subdomain, ownerId, newPodId, SessionStatus.ACTIVE, createdAtEpochMs);
    }

    /** Copy with a new status. */
    public Session withStatus(SessionStatus newStatus) {
        return new Session(sessionId, subdomain, ownerId, podId, newStatus, createdAtEpochMs);
    }
}
