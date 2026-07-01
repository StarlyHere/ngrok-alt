package com.tunnel.pod;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.TunnelConstants;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Entry point for inbound (QA6-like) traffic (BUILD-CHECKLIST.md Part 2),
 * implemented as a servlet filter rather than a catch-all controller.
 *
 * <p>A filter lets the {@code /tunnel} WebSocket handshake pass straight through
 * to the WebSocket handler (no handler-mapping precedence fight), while every
 * other request is forwarded down the owning client's tunnel as a multiplexed
 * stream and the response relayed back. In Part 3 the Router fronts this with
 * sticky {@code session→pod} routing.
 */
@Component
public class TunnelForwardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TunnelForwardFilter.class);

    /** Paths handled by the pod itself (not forwarded). */
    private static final String WS_PATH = "/tunnel";
    private static final String ACTUATOR_PREFIX = "/actuator";

    /** Hop-by-hop / framing headers we must not copy verbatim across the relay. */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive");

    private final ConnectionRegistry registry;

    public TunnelForwardFilter(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (WS_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response); // let the WebSocket handshake through
            return;
        }

        String sessionId = request.getHeader(TunnelConstants.HEADER_SESSION);
        Optional<PodConnection> target = registry.resolve(sessionId);
        if (target.isEmpty()) {
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, ErrorCodes.TUNNEL_NOT_FOUND);
            return;
        }

        HttpRequestMessage req = toRequestMessage(request);
        try {
            relay(response, target.get().forward(req));
        } catch (IOException e) {
            log.warn("forward failed for {} {}: {}", req.method(), req.path(), e.toString());
            writeError(response, HttpServletResponse.SC_BAD_GATEWAY, ErrorCodes.TUNNEL_NOT_FOUND);
        }
    }

    private HttpRequestMessage toRequestMessage(HttpServletRequest request) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        byte[] body = request.getInputStream().readAllBytes();
        return new HttpRequestMessage(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString() == null ? "" : request.getQueryString(),
                headers,
                body);
    }

    private void relay(HttpServletResponse response, HttpResponseMessage resp) throws IOException {
        response.setStatus(resp.status());
        for (Map.Entry<String, List<String>> e : resp.headers().entrySet()) {
            if (SKIP_RESPONSE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String v : e.getValue()) {
                response.addHeader(e.getKey(), v);
            }
        }
        response.getOutputStream().write(resp.body());
        response.getOutputStream().flush();
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader(TunnelConstants.HEADER_POD, "pod");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
