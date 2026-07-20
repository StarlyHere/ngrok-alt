package com.tunnel.pod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tunnel Pod (PRD-v2.md §6). Accepts a developer's outbound WebSocket
 * connection, holds it, and reuses it in reverse to push inbound HTTP requests
 * down as multiplexed streams. In Part 2 it serves a single client end-to-end;
 * Redis self-registration and routing arrive in Part 3.
 */
@SpringBootApplication
@EnableScheduling
public class PodApplication {

    public static void main(String[] args) {
        SpringApplication.run(PodApplication.class, args);
    }
}
