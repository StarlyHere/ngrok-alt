package com.tunnel.protocol.transport;

import java.net.URI;
import java.util.Map;

/**
 * The transport abstraction seam (PRD-v2.md §8).
 *
 * <p>Every candidate transport — WebSocket+mux (default) and SSH (second
 * transport) — satisfies the same contract: "one outbound connection, many
 * reverse streams." A {@code TunnelProvider} knows how to establish that single
 * outbound {@link TunnelConnection}; the connection itself handles stream
 * multiplexing in both directions.
 *
 * <p>Implementations live in their respective modules (e.g. a WebSocket provider
 * in {@code client}/{@code pod}); this interface and its companions are the only
 * thing the rest of the system depends on, which is what keeps the transport
 * pluggable and the Go client cheap to build.
 */
public interface TunnelProvider {

    /**
     * Stable identifier for this transport, e.g. {@code "ws"} or {@code "ssh"}.
     * Used in logs, metrics, and the transport comparison.
     */
    String name();

    /**
     * Establish the single long-lived outbound connection to a tunnel pod.
     *
     * @param endpoint the pod endpoint (e.g. {@code wss://pod-3:8443/tunnel})
     * @param headers  handshake metadata (auth token, session id, trace id, …)
     * @return an open, multiplexed connection
     * @throws Exception if the connection cannot be established
     */
    TunnelConnection open(URI endpoint, Map<String, String> headers) throws Exception;
}
