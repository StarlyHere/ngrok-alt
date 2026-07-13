package com.tunnel.webuiplaceholder;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceholderController {

    @GetMapping("/ui/graphql/**")
    public Map<String, String> placeholder() {
        return Map.of("service", "webui-placeholder", "route", "normal");
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
