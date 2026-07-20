package com.tunnel.client;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The tunnel client binary (PRD-v2.md §7). A deliberately thin Spring Boot app
 * with no embedded web server ({@link WebApplicationType#NONE}); all networking
 * uses the JDK directly. {@link TunnelCliRunner} parses {@code tunnel http
 * <port>} and runs the session.
 */
@SpringBootApplication
public class ClientApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(ClientApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
