package com.tunnel.webuisim;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebUiController {

    @GetMapping("/ui/graphql/generateTTSPreview")
    public Map<String, String> generateTTSPreview() {
        return Map.of(
                "service", "webui-sim",
                "origin", "localhost:4000");
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
