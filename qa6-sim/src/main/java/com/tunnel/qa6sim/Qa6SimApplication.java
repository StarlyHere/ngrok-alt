package com.tunnel.qa6sim;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * QA6 Simulator (PRD-v2.md §6). Generates QA6-like traffic at the Router,
 * targeting sessions by {@code X-Tunnel-Session} and injecting an
 * {@code X-Tunnel-Trace} correlation id so the full path is observable. A thin
 * non-web app; {@link Qa6Simulator} runs the load then exits.
 */
@SpringBootApplication
public class Qa6SimApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(Qa6SimApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
