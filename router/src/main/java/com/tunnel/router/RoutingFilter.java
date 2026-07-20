package com.tunnel.router;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.TunnelConstants;
import com.tunnel.router.PodResolver.PodTarget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The Router's data path (BUILD-CHECKLIST.md Part 3): resolve the session →
 * owning pod via Redis, then forward the request to that pod's data plane,
 * propagating a correlation/trace id. A miss is replayed through the original
 * QA ingress without the LocalConnect selector/session cookies, so it reaches
 * the normal WebUI backend instead of selecting this canary again.
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

    @Value("${router.cookie-name:remoteDebugConf}")
    private String cookieName;

    @Value("${router.selector-cookie-name:sprLocalConnect}")
    private String selectorCookieName;

    @Value("${router.webui-fallback-url:}")
    private String webuiFallbackUrl;

    @Value("${router.fallback-environment:}")
    private String fallbackEnvironment;

    @Value("${router.fallback-domain-suffix:}")
    private String fallbackDomainSuffix;

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
        // Health endpoints must be handled by Spring Boot Actuator rather than
        // being interpreted as tunnel traffic. Kubernetes calls the readiness
        // and liveness variants directly.
        String requestUri = request.getRequestURI();
        if ("/actuator/health".equals(requestUri)
                || requestUri.startsWith("/actuator/health/")) {
            chain.doFilter(request, response);
            return;
        }

        String traceId = ensureTrace(request);
        String sessionId = resolveSession(request);
        if (sessionId == null) {
            log.info("trace {} → no session (host={}, header={}) → webui fallback",
                    traceId, request.getHeader("Host"), request.getHeader(TunnelConstants.HEADER_SESSION));
            forwardToWebUi(request, response, traceId, "no-session");
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
            log.info("trace {} → session {} has no live pod → webui fallback", traceId, redact(sessionId));
            forwardToWebUi(request, response, traceId, "no-pod");
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

    /** Session id: explicit header → remoteDebugConf cookie → Host subdomain. */
    private String resolveSession(HttpServletRequest request) {
        // 1. explicit header (set by gateway or test tooling)
        String header = request.getHeader(TunnelConstants.HEADER_SESSION);
        if (header != null && !header.isBlank()) {
            return header;
        }
        // 2. session cookie (browser WebUI path)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        // 3. subdomain (backward compat with Host-based routing)
        String subdomain = subdomainOf(request.getHeader("Host"));
        return resolver.sessionForSubdomain(subdomain).orElse(null);
    }

    /**
     * Replay a tunnel miss through the original ingress URL. Removing the two
     * LocalConnect cookies is essential: retaining the selector would send the
     * replay back to this canary and create a loop. WEBUI_FALLBACK_URL remains
     * an optional explicit override for environments that have a fixed normal
     * WebUI upstream.
     */
    private void forwardToWebUi(HttpServletRequest request, HttpServletResponse response,
                                String traceId, String reason) throws IOException {
        String fallbackBaseUrl = fallbackBaseUrl(
                request, webuiFallbackUrl, fallbackEnvironment, fallbackDomainSuffix);
        if (fallbackBaseUrl == null) {
            count("not-found");
            writeError(response, ErrorCodes.TUNNEL_NOT_FOUND);
            return;
        }
        String url = fallbackBaseUrl + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        log.info("trace {} → {} → original webui ingress {}", traceId, reason, url);
        count("webui-fallback");

        try {
            byte[] body = request.getInputStream().readAllBytes();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method(request.getMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));

            Enumeration<String> names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (RESTRICTED.contains(lowerName)
                        || TunnelConstants.HEADER_SESSION.equalsIgnoreCase(name)) {
                    continue;
                }
                for (String v : Collections.list(request.getHeaders(name))) {
                    if ("cookie".equals(lowerName)) {
                        String cookies = withoutRoutingCookies(v, selectorCookieName, cookieName);
                        if (!cookies.isBlank()) {
                            b.header(name, cookies);
                        }
                    } else {
                        b.header(name, v);
                    }
                }
            }
            b.header(TunnelConstants.HEADER_TRACE, traceId);

            relay(response, httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("trace {} → original ingress fallback failed", traceId, e);
            writeError(response, ErrorCodes.TUNNEL_NOT_FOUND);
        }
    }

    /** Resolve an explicit override or reconstruct the original public ingress base URL. */
    static String fallbackBaseUrl(HttpServletRequest request, String configuredUrl,
                                  String environment, String domainSuffix) {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return stripTrailingSlashes(configuredUrl);
        }

        String host = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        if (!isAllowedIngressHost(host, environment, domainSuffix)) {
            host = firstForwardedValue(request.getHeader("Host"));
        }
        if (!isAllowedIngressHost(host, environment, domainSuffix)) {
            return null;
        }

        // QA ingress URLs are HTTPS. Trusting X-Forwarded-Proto=http here can
        // relay a redirect to the browser, which would resend the selector
        // cookie and enter the LocalConnect canary again.
        return "https://" + host;
    }

    private static String firstForwardedValue(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }

    /** Only QA ingress hosts are valid dynamic fallback targets; do not become an open proxy. */
    private static boolean isAllowedIngressHost(String host, String environment, String domainSuffix) {
        if (host == null || host.isBlank()
                || environment == null || environment.isBlank()
                || domainSuffix == null || domainSuffix.isBlank()
                || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0 || host.indexOf('@') >= 0) {
            return false;
        }
        String hostname = host;
        int colon = hostname.lastIndexOf(':');
        if (colon > 0 && hostname.indexOf(':') == colon) {
            String port = hostname.substring(colon + 1);
            if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
                return false;
            }
            int portNumber;
            try {
                portNumber = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                return false;
            }
            if (portNumber < 1 || portNumber > 65_535) {
                return false;
            }
            hostname = hostname.substring(0, colon);
        }
        hostname = hostname.toLowerCase(Locale.ROOT);
        if (!isValidHostname(hostname)) {
            return false;
        }
        String suffix = domainSuffix.toLowerCase(Locale.ROOT);
        if (!suffix.startsWith(".")) {
            suffix = "." + suffix;
        }
        if (!hostname.endsWith(suffix) || hostname.length() == suffix.length()) {
            return false;
        }
        String ingressName = hostname.substring(0, hostname.length() - suffix.length());
        return containsEnvironmentToken(ingressName, environment.toLowerCase(Locale.ROOT));
    }

    private static boolean containsEnvironmentToken(String ingressName, String environment) {
        int from = 0;
        while (from <= ingressName.length() - environment.length()) {
            int index = ingressName.indexOf(environment, from);
            if (index < 0) {
                return false;
            }
            int end = index + environment.length();
            boolean startsAtBoundary = index == 0 || isHostTokenSeparator(ingressName.charAt(index - 1));
            boolean endsAtBoundary = end == ingressName.length()
                    || isHostTokenSeparator(ingressName.charAt(end));
            if (startsAtBoundary && endsAtBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isHostTokenSeparator(char value) {
        return value == '-' || value == '.';
    }

    private static boolean isValidHostname(String hostname) {
        if (!Character.isLetterOrDigit(hostname.charAt(0))
                || !Character.isLetterOrDigit(hostname.charAt(hostname.length() - 1))) {
            return false;
        }
        for (int index = 1; index < hostname.length() - 1; index++) {
            char value = hostname.charAt(index);
            if (!Character.isLetterOrDigit(value) && value != '-' && value != '.') {
                return false;
            }
        }
        return true;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    static String withoutRoutingCookies(String cookieHeader, String selectorName, String sessionName) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .filter(cookie -> {
                    int equals = cookie.indexOf('=');
                    String name = equals < 0 ? cookie : cookie.substring(0, equals).trim();
                    return !name.equals(selectorName) && !name.equals(sessionName);
                })
                .filter(cookie -> !cookie.isBlank())
                .collect(java.util.stream.Collectors.joining("; "));
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
