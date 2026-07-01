package com.tunnel.protocol.dto;

/** Lifecycle state of a tunnel session as tracked in Redis (PRD-v2.md §5). */
public enum SessionStatus {
    /** Session id allocated by the Coordinator; client not yet connected. */
    PENDING,
    /** Client connected and registered to its owning pod; routable. */
    ACTIVE,
    /** Client lost; ownership TTL expiring. Router returns tunnel-not-found. */
    EXPIRED,
    /** Explicitly torn down by the owner. */
    CLOSED
}
