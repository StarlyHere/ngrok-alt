package com.tunnel.qa6gateway;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.TunnelConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The decision layer (Feature: QA6 Gateway). For every request:
 * <ol>
 *   <li>no tunnel indicator → serve normal QA6 traffic (a placeholder for now);</li>
 *   <li>indicator present + session VALID → normalize to {@code X-Tunnel-Session}
 *       and forward to the existing Router (which does the authoritative routing);</li>
 *   <li>indicator present + session INVALID → {@code tunnel-not-found} (strict);</li>
 *   <li>Redis error → fail-closed for tunnel requests.</li>
 * </ol>
 * Reuses the Router's forward/relay shape (JDK {@link HttpClient}, restricted
 * request headers, hop-by-hop response headers). The Router is the only upstream
 * for tunnel traffic, so there is no user-controlled forward target (no SSRF).
 */
@Component
public class GatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayFilter.class);

    /** Request headers the JDK HttpClient forbids us from setting. */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "host", "upgrade", "expect",
            "transfer-encoding", "date", "via", "warning", "keep-alive");

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive");

    private final GatewayProperties props;
    private final IndicatorExtractor indicator;
    private final SessionGate gate;
    private final MeterRegistry meters;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GatewayFilter(GatewayProperties props, IndicatorExtractor indicator,
                         SessionGate gate, MeterRegistry meters) {
        this.props = props;
        this.indicator = indicator;
        this.gate = gate;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The gateway's own actuator/health endpoints are not gateway-routed.
        if (request.getRequestURI().startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        String traceId = ensureTrace(request);   // the Gateway is the trace origin
        Optional<String> session = indicator.extract(request);

        if (session.isEmpty()) {
            count("normal");
            writeNormal(response);
            return;
        }
        String sessionId = session.get();

        switch (gate.validate(sessionId)) {
            case VALID -> {
                count("forwarded");
                forwardToRouter(request, response, sessionId, traceId);
            }
            case INVALID -> {
                if (props.strictNotFound()) {
                    log.info("trace {} → session {} invalid → tunnel-not-found", traceId, redact(sessionId));
                    count("not-found");
                    writeError(response, HttpServletResponse.SC_BAD_GATEWAY, ErrorCodes.TUNNEL_NOT_FOUND);
                } else {
                    count("normal-fallthrough");
                    writeNormal(response);
                }
            }
            case REDIS_ERROR -> {
                if (props.failClosed()) {
                    log.warn("trace {} → redis error → fail-closed (session {})", traceId, redact(sessionId));
                    count("redis-error");
                    writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCodes.INTERNAL_ERROR);
                } else {
                    count("normal-failopen");
                    writeNormal(response);
                }
            }
        }
    }

    /** Forward a validated tunnel request to the Router, normalizing the indicator. */
    private void forwardToRouter(HttpServletRequest request, HttpServletResponse response,
                                 String sessionId, String traceId) throws IOException {
        String url = props.routerUrl() + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        log.info("trace {} → session {} → router {}", traceId, redact(sessionId), url);

        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String v : Collections.list(request.getHeaders(name))) {
                b.header(name, v);
            }
        }
        // Normalize the indicator into the header the Router understands + carry the trace.
        
        b.header(TunnelConstants.HEADER_SESSION, sessionId);
        b.header(TunnelConstants.HEADER_TRACE, traceId);

        try {
            HttpResponse<byte[]> routerResp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            relay(response, routerResp);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("trace {} → router forward failed: {}", traceId, e.toString());
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, ErrorCodes.TUNNEL_NOT_FOUND);
        }
    }

    private void relay(HttpServletResponse response, HttpResponse<byte[]> upstream) throws IOException {
        response.setStatus(upstream.statusCode());
        for (Map.Entry<String, List<String>> e : upstream.headers().map().entrySet()) {
            if (SKIP_RESPONSE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String v : e.getValue()) {
                response.addHeader(e.getKey(), v);
            }
        }
        response.getOutputStream().write(upstream.body());
        response.getOutputStream().flush();
    }

    private void writeNormal(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getOutputStream().write(props.normalPlaceholderBody().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static String ensureTrace(HttpServletRequest request) {
        String trace = request.getHeader(TunnelConstants.HEADER_TRACE);
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private void count(String outcome) {
        Counter.builder("tunnel_gateway_requests_total")
                .description("QA6 Gateway decision outcomes").tag("outcome", outcome)
                .register(meters).increment();
    }

    private static String redact(String s) {
        return s == null || s.length() <= 6 ? "******" : s.substring(0, 6) + "…";
    }
}
