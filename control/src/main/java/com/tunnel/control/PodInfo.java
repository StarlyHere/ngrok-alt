package com.tunnel.control;

/**
 * A snapshot of a live pod as read from Redis, used for Least-Connections
 * assignment (PRD-v2.md §10).
 *
 * @param id    pod id
 * @param host  pod host (reachable by clients and the router)
 * @param port  pod port (serves both the WS tunnel and the data plane)
 * @param conns current connection count (the least-conn signal)
 */
public record PodInfo(String id, String host, int port, int conns, boolean secure) {

    /** The WebSocket endpoint a client opens its outbound tunnel to (wss when TLS). */
    public String wsUrl() {
        return (secure ? "wss://" : "ws://") + host + ":" + port + "/tunnel";
    }
}
