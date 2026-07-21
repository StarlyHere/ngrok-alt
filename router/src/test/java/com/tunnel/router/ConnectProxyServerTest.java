package com.tunnel.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConnectProxyServerTest {

    @Test
    void extractsSessionFromBasicProxyCredentials() {
        String credentials = Base64.getEncoder().encodeToString(
                "session-123:localconnect".getBytes(StandardCharsets.UTF_8));

        assertThat(ConnectProxyServer.sessionId(new String[]{
                "CONNECT service:8080 HTTP/1.1",
                "Proxy-Authorization: Basic " + credentials
        })).isEqualTo("session-123");
    }

    @Test
    void rejectsInvalidOrMissingSessionCredentials() {
        assertThat(ConnectProxyServer.sessionId(new String[]{"Proxy-Authorization: Basic invalid"}))
                .isNull();
        assertThat(ConnectProxyServer.sessionId(new String[]{"CONNECT service:8080 HTTP/1.1"}))
                .isNull();
    }
}
