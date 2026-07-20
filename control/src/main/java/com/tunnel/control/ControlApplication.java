package com.tunnel.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Control Plane / Coordinator (PRD-v2.md §6). Owns auth (Part 4), session +
 * subdomain issuance, and Least-Connections pod assignment (path A). Deliberately
 * separate from the Router so the control/data-plane split is tangible
 * (PRD-v2.md §15).
 */
@SpringBootApplication
@EnableConfigurationProperties(ControlProperties.class)
@EnableScheduling
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}
