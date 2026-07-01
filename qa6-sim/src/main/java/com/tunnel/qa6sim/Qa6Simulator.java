package com.tunnel.qa6sim;

import com.tunnel.protocol.TunnelConstants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fires QA6-like traffic at the Router (BUILD-CHECKLIST.md Part 3):
 *
 * <pre>
 *   qa6-sim --sessions=&lt;id1&gt;,&lt;id2&gt; [--router=http://localhost:8080]
 *           [--count=20] [--concurrency=5] [--path=/hello]
 * </pre>
 *
 * Each request carries the target {@code X-Tunnel-Session} and a fresh
 * {@code X-Tunnel-Trace} id (which propagates Router→Pod→Client→Service and shows
 * up in the client's {@code :4040} inspector). Reports status + latency.
 */
@Component
public class Qa6Simulator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Qa6Simulator.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void run(ApplicationArguments args) {
        List<String> sessions = csv(opt(args, "sessions", null));
        if (sessions.isEmpty()) {
            log.info("usage: qa6-sim --sessions=<id1>,<id2> [--router=http://localhost:8080] "
                    + "[--count=20] [--concurrency=5] [--path=/hello]");
            return;
        }
        String router = opt(args, "router", "http://localhost:8080");
        String path = opt(args, "path", "/hello");
        int count = Integer.parseInt(opt(args, "count", "20"));
        int concurrency = Integer.parseInt(opt(args, "concurrency", "5"));

        log.info("QA6 sim → {} : {} requests across {} session(s), concurrency {}",
                router, count, sessions.size(), concurrency);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> perSession = new ConcurrentHashMap<>();
        List<Long> latencies = new ArrayList<>();
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String session = sessions.get(i % sessions.size());
            futures.add(pool.submit(() -> fireOne(router, path, session, statusCounts, perSession)));
        }
        for (Future<Long> f : futures) {
            try {
                Long ms = f.get();
                if (ms != null) {
                    latencies.add(ms);
                }
            } catch (Exception e) {
                log.warn("request failed: {}", e.toString());
            }
        }
        pool.shutdownNow();
        report(count, statusCounts, perSession, latencies);
    }

    private Long fireOne(String router, String path, String session,
                         Map<Integer, AtomicInteger> statusCounts,
                         Map<String, AtomicInteger> perSession) {
        String trace = UUID.randomUUID().toString();
        HttpRequest req = HttpRequest.newBuilder(URI.create(router + path))
                .timeout(Duration.ofSeconds(15))
                .header(TunnelConstants.HEADER_SESSION, session)
                .header(TunnelConstants.HEADER_TRACE, trace)
                .GET()
                .build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - start) / 1_000_000;
            statusCounts.computeIfAbsent(resp.statusCode(), k -> new AtomicInteger()).incrementAndGet();
            perSession.computeIfAbsent(redact(session), k -> new AtomicInteger()).incrementAndGet();
            log.debug("trace {} session {} → {} in {}ms : {}", trace, redact(session),
                    resp.statusCode(), ms, snippet(resp.body()));
            return ms;
        } catch (Exception e) {
            statusCounts.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
            log.warn("trace {} session {} → error: {}", trace, redact(session), e.toString());
            return null;
        }
    }

    private void report(int count, Map<Integer, AtomicInteger> statusCounts,
                        Map<String, AtomicInteger> perSession, List<Long> latencies) {
        log.info("---- QA6 sim results ----");
        log.info("requests sent: {}", count);
        statusCounts.forEach((status, n) ->
                log.info("  HTTP {}: {}", status < 0 ? "ERR" : status, n.get()));
        perSession.forEach((session, n) -> log.info("  session {}: {} req", session, n.get()));
        if (!latencies.isEmpty()) {
            latencies.sort(Long::compareTo);
            long min = latencies.get(0);
            long max = latencies.get(latencies.size() - 1);
            long p50 = latencies.get(latencies.size() / 2);
            long sum = latencies.stream().mapToLong(Long::longValue).sum();
            log.info("  latency ms: min {}, p50 {}, max {}, avg {}", min, p50, max, sum / latencies.size());
        }
    }

    // --- helpers ---

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String opt(ApplicationArguments args, String name, String fallback) {
        List<String> v = args.getOptionValues(name);
        return v == null || v.isEmpty() ? fallback : v.get(0);
    }

    private static String redact(String s) {
        return s == null || s.length() <= 6 ? "******" : s.substring(0, 6) + "…";
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }
}
