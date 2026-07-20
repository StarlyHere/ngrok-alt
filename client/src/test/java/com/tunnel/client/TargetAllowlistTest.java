package com.tunnel.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * §9.4 SSRF scenario: a tunnel on port 3000 must refuse to reach any other
 * localhost port (e.g. 5432). The client's allowlist is the control under test.
 */
class TargetAllowlistTest {

    private final LocalForwarder forwarder = new LocalForwarder(3000);

    @Test
    void allowsTheNamedLocalhostPort() {
        var target = forwarder.resolveTarget(
                new HttpRequestMessage("GET", "/hello", "name=Ada", Map.of(), new byte[0]));
        assertNotNull(target);
        assertEquals("127.0.0.1", target.getHost());
        assertEquals(3000, target.getPort());
    }

    @Test
    void blocksAbsoluteTargetToAnotherPort() {
        // A malicious request tries to pivot to the Postgres port via an
        // absolute-form request target.
        var target = forwarder.resolveTarget(
                new HttpRequestMessage("GET", "http://127.0.0.1:5432/secret", "", Map.of(), new byte[0]));
        assertNull(target, "forwarding to 127.0.0.1:5432 must be blocked by the allowlist");
    }

    @Test
    void blocksAbsoluteTargetToExternalHost() {
        var target = forwarder.resolveTarget(
                new HttpRequestMessage("GET", "http://169.254.169.254/latest/meta-data", "", Map.of(), new byte[0]));
        assertNull(target, "forwarding to a non-loopback host must be blocked");
    }

    @Test
    void forwardReturns403ForDisallowedTarget() throws Exception {
        HttpResponseMessage resp = forwarder.forward(
                new HttpRequestMessage("GET", "http://127.0.0.1:5432/secret", "", Map.of(), new byte[0]));
        assertEquals(403, resp.status());
        assertEquals(true, new String(resp.body(), StandardCharsets.UTF_8).contains(ErrorCodes.TARGET_NOT_ALLOWED));
    }
}
