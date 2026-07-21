package com.tunnel.qa6gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Exercises the decision logic end to end against a stub "Router". Confirms:
 * normal traffic → placeholder; valid tunnel request (header or cookie) → forwarded
 * with X-Tunnel-Session normalized; invalid → tunnel-not-found; redis-error →
 * fail-closed.
 */
class GatewayFilterTest {

    private HttpServer stubRouter;
    private String routerUrl;
    private final AtomicReference<String> seenSession = new AtomicReference<>();
    private final AtomicReference<String> seenTrace = new AtomicReference<>();

    private SessionGate gate;
    private GatewayFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        stubRouter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubRouter.createContext("/", exchange -> {
            seenSession.set(exchange.getRequestHeaders().getFirst("X-Tunnel-Session"));
            seenTrace.set(exchange.getRequestHeaders().getFirst("X-Tunnel-Trace"));
            byte[] body = "{\"service\":\"dev-service\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stubRouter.start();
        routerUrl = "http://127.0.0.1:" + stubRouter.getAddress().getPort();

        GatewayProperties props = new GatewayProperties(routerUrl, "remoteDebugConf", true, true, null);
        gate = mock(SessionGate.class);
        filter = new GatewayFilter(props, new IndicatorExtractor(props), gate, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        stubRouter.stop(0);
    }

    private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req, resp, new MockFilterChain());
        return resp;
    }

    @Test
    void noIndicatorServesNormalPlaceholder() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/anything");
        MockHttpServletResponse resp = run(req);
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"route\":\"normal\""));
    }

    private static SessionGate.ValidationResult valid(String pathPatterns) {
        return SessionGate.ValidationResult.of(SessionGate.Result.VALID, pathPatterns);
    }

    @Test
    void validSessionViaHeaderIsForwardedToRouter() throws Exception {
        when(gate.validate("sess-abc")).thenReturn(valid(null));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/hello");
        req.addHeader("X-Tunnel-Session", "sess-abc");

        MockHttpServletResponse resp = run(req);

        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("dev-service"));
        assertEquals("sess-abc", seenSession.get(), "session normalized into X-Tunnel-Session");
        assertNotNull(seenTrace.get(), "gateway originates a trace id");
    }

    @Test
    void validSessionViaCookieIsForwarded() throws Exception {
        when(gate.validate("sess-cookie")).thenReturn(valid(null));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/hello");
        req.setCookies(new Cookie("remoteDebugConf", "sess-cookie"));

        MockHttpServletResponse resp = run(req);

        assertEquals(200, resp.getStatus());
        assertEquals("sess-cookie", seenSession.get());
    }

    @Test
    void microserviceSuffixIsRemovedBeforeSessionValidation() throws Exception {
        when(gate.validate("sess-cookie")).thenReturn(valid(null));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/hello");
        req.setCookies(new Cookie("remoteDebugConf", "sess-cookie:process-engine"));

        MockHttpServletResponse resp = run(req);

        assertEquals(200, resp.getStatus());
        assertEquals("sess-cookie", seenSession.get());
    }

    @Test
    void matchingPathPatternIsForwarded() throws Exception {
        when(gate.validate("sess-path")).thenReturn(valid("/process/**,/ui/graphql/**"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/process/start");
        req.addHeader("X-Tunnel-Session", "sess-path");

        MockHttpServletResponse resp = run(req);

        assertEquals(200, resp.getStatus());
        assertEquals("sess-path", seenSession.get());
    }

    @Test
    void nonMatchingPathPatternFallsThroughToNormalQA6() throws Exception {
        when(gate.validate("sess-path")).thenReturn(valid("/process/**"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/unrelated/endpoint");
        req.addHeader("X-Tunnel-Session", "sess-path");

        MockHttpServletResponse resp = run(req);

        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"route\":\"normal\""));
        assertNull(seenSession.get(), "router must not have been called");
    }

    @Test
    void invalidSessionReturnsTunnelNotFound() throws Exception {
        when(gate.validate("bad")).thenReturn(SessionGate.ValidationResult.of(SessionGate.Result.INVALID));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/hello");
        req.addHeader("X-Tunnel-Session", "bad");

        MockHttpServletResponse resp = run(req);

        assertEquals(502, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("tunnel-not-found"));
        assertNull(seenSession.get(), "router should not have been called");
    }

    @Test
    void redisErrorFailsClosed() throws Exception {
        when(gate.validate("x")).thenReturn(SessionGate.ValidationResult.of(SessionGate.Result.REDIS_ERROR));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/hello");
        req.addHeader("X-Tunnel-Session", "x");

        MockHttpServletResponse resp = run(req);

        assertEquals(503, resp.getStatus());
    }
}
