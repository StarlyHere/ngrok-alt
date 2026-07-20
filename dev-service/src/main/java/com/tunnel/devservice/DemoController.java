package com.tunnel.devservice;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A few trivial endpoints so we have something concrete to tunnel to and verify
 * end-to-end (BUILD-CHECKLIST.md Part 1: {@code /hello}, {@code /echo}).
 */
@RestController
public class DemoController {

    /** Liveness/greeting endpoint: {@code GET /hello?name=Ada}. */
    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
        return Map.of(
                "message", "hello, " + name,
                "service", "dev-service",
                "origin", "anushka-macbook-localhost-3000",
                "timestamp", Instant.now().toString());
    }

    /** Echoes the JSON body back, wrapped with metadata: {@code POST /echo}. */
    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody(required = false) Map<String, Object> body) {
        return Map.of(
                "service", "dev-service",
                "echo", body == null ? Map.of() : body);
    }

    /** Simple health probe: {@code GET /healthz}. */
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
