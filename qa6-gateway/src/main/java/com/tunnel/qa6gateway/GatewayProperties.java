package com.tunnel.qa6gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration, bound from {@code gateway.*}.
 *
 * @param routerUrl             where tunnel/debug requests are forwarded (the
 *                              existing Router; e.g. {@code http://router:8080})
 * @param cookieName            cookie whose value is the session id ({@code remoteDebugConf})
 * @param strictNotFound        if true, an indicator with an invalid/expired session
 *                              returns {@code tunnel-not-found} (else falls through to normal)
 * @param failClosed            if true, a Redis error fails tunnel requests closed
 *                              (normal traffic is unaffected — it needs no Redis)
 * @param normalPlaceholderBody body served for normal (non-tunnel) traffic
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String routerUrl,
        String cookieName,
        boolean strictNotFound,
        boolean failClosed,
        String normalPlaceholderBody) {

    public GatewayProperties {
        if (routerUrl == null || routerUrl.isBlank()) {
            routerUrl = "http://localhost:8080";
        }
        if (cookieName == null || cookieName.isBlank()) {
            cookieName = "remoteDebugConf";
        }
        if (normalPlaceholderBody == null) {
            normalPlaceholderBody = "{\"service\":\"qa6-gateway\",\"route\":\"normal\"}";
        }
    }
}
