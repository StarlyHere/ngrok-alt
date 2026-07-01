package com.tunnel.router;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.TunnelConstants;
import com.tunnel.router.PodResolver.PodTarget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The Router's data path (BUILD-CHECKLIST.md Part 3): resolve the session →
 * owning pod via Redis, then forward the request to that pod's data plane,
 * propagating a correlation/trace id. A miss returns a clean
 * {@code tunnel-not-found} (no hang) — the visible result of TTL expiry.
 */
@Component
public class RoutingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RoutingFilter.class);

    /** Headers the JDK HttpClient forbids us from setting on the forwarded request. */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "host", "upgrade", "expect",
            "transfer-encoding", "date", "via", "warning", "keep-alive");

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive");

    private final PodResolver resolver;
    private final RateLimiter rateLimiter;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public RoutingFilter(PodResolver resolver, RateLimiter rateLimiter,
                         io.micrometer.core.instrument.MeterRegistry meters) {
        this.resolver = resolver;
        this.rateLimiter = rateLimiter;
        this.meters = meters;
    }

    private void count(String outcome) {
        io.micrometer.core.instrument.Counter.builder("tunnel_router_requests_total")
                .description("Router request outcomes").tag("outcome", outcome)
                .register(meters).increment();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The router itself answers its health probe.
        if ("/healthz".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String traceId = ensureTrace(request);
        String sessionId = resolveSession(request);
        if (sessionId == null) {
            log.info("trace {} → no session (host={}, header={}) → tunnel-not-found",
                    traceId, request.getHeader("Host"), request.getHeader(TunnelConstants.HEADER_SESSION));
            count("not-found");
            writeError(response, ErrorCodes.TUNNEL_NOT_FOUND);
            return;
        }

        // Request-rate cap per session (§9.2.6).
        if (!rateLimiter.allow(sessionId, System.currentTimeMillis())) {
            log.info("trace {} → session {} rate-limited", traceId, redact(sessionId));
            count("rate-limited");
            writeError(response, 429, ErrorCodes.QUOTA_EXCEEDED);
            return;
        }

        Optional<PodTarget> target = resolver.targetForSession(sessionId);
        if (target.isEmpty()) {
            log.info("trace {} → session {} has no live pod → tunnel-not-found", traceId, redact(sessionId));
            count("not-found");
            writeError(response, ErrorCodes.TUNNEL_NOT_FOUND);
            return;
        }

        count("forwarded");
        forward(request, response, target.get(), sessionId, traceId);
    }

    private void forward(HttpServletRequest request, HttpServletResponse response,
                         PodTarget pod, String sessionId, String traceId) throws IOException {
        String url = pod.baseUrl() + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        log.info("trace {} → session {} → {} → {}", traceId, redact(sessionId), pod.podId(), url);

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
        // Ensure the pod can resolve the tunnel and the trace propagates downstream.
        b.header(TunnelConstants.HEADER_SESSION, sessionId);
        b.header(TunnelConstants.HEADER_TRACE, traceId);

        try {
            HttpResponse<byte[]> podResp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            relay(response, podResp);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("trace {} → forward to {} failed: {}", traceId, pod.podId(), e.toString());
            writeError(response, ErrorCodes.TUNNEL_NOT_FOUND);
        }
    }

    private void relay(HttpServletResponse response, HttpResponse<byte[]> podResp) throws IOException {
        response.setStatus(podResp.statusCode());
        for (Map.Entry<String, List<String>> e : podResp.headers().map().entrySet()) {
            if (SKIP_RESPONSE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String v : e.getValue()) {
                response.addHeader(e.getKey(), v);
            }
        }
        response.getOutputStream().write(podResp.body());
        response.getOutputStream().flush();
    }

    /** Session id comes from the explicit header, or the Host subdomain. */
    private String resolveSession(HttpServletRequest request) {
        String header = request.getHeader(TunnelConstants.HEADER_SESSION);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String subdomain = subdomainOf(request.getHeader("Host"));
        return resolver.sessionForSubdomain(subdomain).orElse(null);
    }

    private static String subdomainOf(String host) {
        if (host == null) {
            return null;
        }
        int colon = host.indexOf(':');
        String hostname = colon >= 0 ? host.substring(0, colon) : host;
        if (hostname.endsWith(TunnelConstants.TUNNEL_DOMAIN_SUFFIX)) {
            return hostname.substring(0, hostname.length() - TunnelConstants.TUNNEL_DOMAIN_SUFFIX.length());
        }
        int dot = hostname.indexOf('.');
        return dot > 0 ? hostname.substring(0, dot) : null;
    }

    private static String ensureTrace(HttpServletRequest request) {
        String trace = request.getHeader(TunnelConstants.HEADER_TRACE);
        return (trace == null || trace.isBlank()) ? UUID.randomUUID().toString() : trace;
    }

    private void writeError(HttpServletResponse response, String code) throws IOException {
        writeError(response, HttpServletResponse.SC_BAD_GATEWAY, code);
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static String redact(String s) {
        return s == null || s.length() <= 6 ? "******" : s.substring(0, 6) + "…";
    }
}
