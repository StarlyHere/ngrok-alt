package com.tunnel.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@code tunnel login} (PRD-v2.md §7, §9.2.2): obtain a bearer token
 * from the Coordinator and store it at {@code ~/.tunnel/config} ({@code 0600}).
 */
public class LoginCommand {

    private static final Logger log = LoggerFactory.getLogger(LoginCommand.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final TokenStore tokenStore = new TokenStore();

    public void run(URI coordinator, String user) {
        try {
            URI loginUri = coordinator.resolve("/login?user="
                    + URLEncoder.encode(user, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(loginUri)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("login failed: coordinator returned HTTP {}", response.statusCode());
                return;
            }
            String token = response.body().trim();
            tokenStore.save(token, user, coordinator.toString());
            // Never log the token value itself.
            log.info("logged in as '{}'; credentials stored at {} (0600)", user, tokenStore.path());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("login failed: {}", e.toString());
        }
    }
}
