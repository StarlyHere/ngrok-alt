package com.tunnel.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Router (PRD-v2.md §6, §10): the stateless, horizontally-scalable entry point.
 * Reads {@code Host}/{@code X-Tunnel-Session}, does an O(1) sticky Redis lookup
 * {@code session→pod} (path B — never load-balanced), and forwards to the owning
 * pod. A miss is replayed through the original ingress without the
 * LocalConnect routing cookies, allowing the normal WebUI backend to serve it.
 */
@SpringBootApplication
public class RouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouterApplication.class, args);
    }
}
