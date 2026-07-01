package com.tunnel.qa6gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * QA6 Gateway / Decision Layer — the system's front door (:9000).
 *
 * <p>It bifurcates inbound traffic: requests carrying a tunnel/debug indicator
 * (an {@code X-Tunnel-Session} header or a {@code remoteDebugConf} cookie) are
 * normalized to the {@code X-Tunnel-Session} header and forwarded to the existing
 * Router; everything else is treated as normal QA6 traffic. The Router, Coordinator,
 * Pods, and Client are unchanged — this layer is purely additive.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class Qa6GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(Qa6GatewayApplication.class, args);
    }
}
