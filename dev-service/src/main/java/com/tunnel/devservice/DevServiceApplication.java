package com.tunnel.devservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The sample developer service (PRD-v2.md §6). A normal local REST app the
 * developer is debugging — it has no knowledge of the tunnel. In later parts the
 * client forwards tunneled requests to it at {@code 127.0.0.1:3000}.
 */
@SpringBootApplication
public class DevServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevServiceApplication.class, args);
    }
}
