package com.tunnel.webuisim;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebUiController {

    private static final Logger log = LoggerFactory.getLogger(WebUiController.class);

    @GetMapping("/ui/graphql/generateTTSPreview")
    public Map<String, String> generateTTSPreview() {
        return Map.of(
                "service", "webui-sim",
                "origin", "localhost:4000");
    }

    @PostMapping("/_kafka/record")
    public ResponseEntity<Void> kafkaRecord(
            @RequestHeader(value = "X-Tunnel-Session", required = false) String sessionId,
            @RequestBody String body) {
        log.info("kafka record received: session={} body={}", sessionId, body);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
