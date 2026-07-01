package com.tunnel.client;

import java.net.URI;

/**
 * Resolved configuration for one {@code tunnel http <port>} session.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Coordinator mode</b> (default, Part 3): {@code coordinatorEndpoint} is
 *       set; the client asks the Coordinator for a pod assignment each connect.</li>
 *   <li><b>Direct mode</b> (Part 2 style, for debugging): {@code directPodEndpoint}
 *       is set; the client connects straight to that pod with a self-generated id.</li>
 * </ul>
 *
 * @param coordinatorEndpoint Coordinator base URL, or {@code null} in direct mode
 * @param directPodEndpoint   pod WS URL for direct mode, or {@code null}
 * @param targetPort          localhost port to forward to
 * @param sessionId           pinned session id (e.g. {@code --session}); usually
 *                            {@code null} — the Coordinator issues it
 * @param ownerId             owner identity (token-bound in Part 4)
 * @param token               bearer token for the handshake (null in Part 3)
 * @param clientVersion       client binary version string
 * @param inspectorPort       local request inspector port (default 4040)
 * @param heartbeatIntervalMs heartbeat cadence (overridden by the pod's ACK)
 */
public record TunnelConfig(
        URI coordinatorEndpoint,
        URI directPodEndpoint,
        String targetHost,
        int targetPort,
        String sessionId,
        String ownerId,
        String token,
        String clientVersion,
        int inspectorPort,
        long heartbeatIntervalMs) {

    public boolean directMode() {
        return directPodEndpoint != null;
    }
}
