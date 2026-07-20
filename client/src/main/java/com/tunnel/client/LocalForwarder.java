package com.tunnel.client;

import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forwards a tunneled request to the developer's local service at
 * {@code 127.0.0.1:<port>} and captures the response (BUILD-CHECKLIST.md Part 2).
 *
 * <p>The target is fixed to the single localhost port the developer named — this
 * is also the SSRF allowlist boundary that Part 4 formalizes (PRD-v2.md §9.2.4):
 * the client never forwards anywhere else.
 */
public class LocalForwarder {

    /**
     * Headers the JDK {@link HttpClient} forbids callers from setting; they are
     * recomputed by the client for the localhost hop.
     */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "host", "upgrade", "expect",
            "transfer-encoding", "date", "via", "warning", "keep-alive");

    private final HttpClient httpClient;
    private final String targetHost;
    private final int targetPort;

    /** Default target host is loopback (the developer's local app on their machine). */
    public LocalForwarder(int targetPort) {
        this("127.0.0.1", targetPort);
    }

    /**
     * @param targetHost the single host the client is allowed to forward to —
     *                   loopback by default, but a named service in the
     *                   in-cluster demo where the app runs in a separate pod. The
     *                   allowlist is still exactly one host:port (§9.2.4); no
     *                   attacker-supplied target is ever honoured.
     */
    public LocalForwarder(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public int targetPort() {
        return targetPort;
    }

    public HttpResponseMessage forward(HttpRequestMessage req) throws IOException, InterruptedException {
        URI target = resolveTarget(req);
        if (target == null) {
            // SSRF guard (§9.2.4): the request tried to steer us off the allowlisted
            // localhost:<port>. Refuse without dialing anything.
            return new HttpResponseMessage(403, Map.of(),
                    ("{\"error\":\"" + com.tunnel.protocol.ErrorCodes.TARGET_NOT_ALLOWED + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        HttpRequest.BodyPublisher bodyPublisher = req.body() == null || req.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(req.body());

        HttpRequest.Builder b = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .method(req.method(), bodyPublisher);

        if (req.headers() != null) {
            for (Map.Entry<String, List<String>> e : req.headers().entrySet()) {
                if (RESTRICTED.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (String v : e.getValue()) {
                    b.header(e.getKey(), v);
                }
            }
        }

        HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new HttpResponseMessage(resp.statusCode(), resp.headers().map(), resp.body());
    }

    /**
     * Build the target URI for a request and enforce the allowlist: it must
     * resolve to exactly {@code http://<configured host>:<the one named port>}.
     * Returns null if the request tries to steer elsewhere (e.g. an absolute-form
     * target naming a different port/host) — the SSRF pivot is closed at the
     * source (§9.2.4).
     */
    URI resolveTarget(HttpRequestMessage req) {
        String path = req.path() == null || req.path().isEmpty() ? "/" : req.path();
        String query = req.query() == null || req.query().isEmpty() ? "" : "?" + req.query();
        URI target;
        try {
            if (isAbsolute(path)) {
                target = URI.create(path + query); // proxy-style absolute request target
            } else {
                String normalized = path.startsWith("/") ? path : "/" + path;
                target = URI.create("http://" + targetHost + ":" + targetPort + normalized + query);
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return isAllowed(target) ? target : null;
    }

    private boolean isAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme != null && !scheme.equalsIgnoreCase("http")) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(targetHost)) {
            return false;
        }
        return uri.getPort() == targetPort;
    }

    private static boolean isAbsolute(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
