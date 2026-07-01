package com.tunnel.client.inspect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ngrok-style local request inspector (PRD-v2.md §7, §11; BUILD-CHECKLIST.md
 * Part 2). Serves a live list of requests/responses that flowed through the
 * tunnel on {@code http://localhost:4040}. Uses the JDK's built-in HTTP server
 * (no Tomcat) to keep the client thin and native-image friendly.
 */
public class RequestInspector {

    private static final Logger log = LoggerFactory.getLogger(RequestInspector.class);
    private static final int MAX_RECORDS = 200;

    private final int port;
    private final Deque<Record> records = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private HttpServer server;

    public RequestInspector(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/requests", this::handleApi);
        server.createContext("/", this::handleIndex);
        server.setExecutor(null); // default executor
        server.start();
        log.info("request inspector on http://localhost:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Begin tracking a request; returns a handle to complete once responded. */
    public Record begin(String method, String path, String traceId, int requestBytes) {
        Record r = new Record(seq.incrementAndGet(), method, path, traceId,
                requestBytes, System.currentTimeMillis());
        synchronized (records) {
            records.addFirst(r);
            while (records.size() > MAX_RECORDS) {
                records.removeLast();
            }
        }
        return r;
    }

    /** Mark a tracked request complete with its response status/size. */
    public void complete(Record r, int status, int responseBytes) {
        r.status = status;
        r.responseBytes = responseBytes;
        r.durationMs = System.currentTimeMillis() - r.startedAtEpochMs;
    }

    /** Mark a tracked request as failed. */
    public void fail(Record r, String error) {
        r.status = -1;
        r.error = error;
        r.durationMs = System.currentTimeMillis() - r.startedAtEpochMs;
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        List<Record> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<>(records);
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(snapshot.get(i).toJson());
        }
        json.append(']');
        respond(exchange, 200, "application/json", json.toString());
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", INDEX_HTML);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** A single inspected request/response, mutated in place as it completes. */
    public static final class Record {
        final long id;
        final String method;
        final String path;
        final String traceId;
        final int requestBytes;
        final long startedAtEpochMs;
        volatile int status;
        volatile int responseBytes;
        volatile long durationMs = -1;
        volatile String error;

        Record(long id, String method, String path, String traceId, int requestBytes, long startedAtEpochMs) {
            this.id = id;
            this.method = method;
            this.path = path;
            this.traceId = traceId == null ? "" : traceId;
            this.requestBytes = requestBytes;
            this.startedAtEpochMs = startedAtEpochMs;
        }

        String toJson() {
            return "{"
                    + "\"id\":" + id
                    + ",\"method\":\"" + esc(method) + "\""
                    + ",\"path\":\"" + esc(path) + "\""
                    + ",\"trace\":\"" + esc(traceId) + "\""
                    + ",\"status\":" + status
                    + ",\"durationMs\":" + durationMs
                    + ",\"requestBytes\":" + requestBytes
                    + ",\"responseBytes\":" + responseBytes
                    + ",\"error\":" + (error == null ? "null" : "\"" + esc(error) + "\"")
                    + "}";
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static final String INDEX_HTML = """
            <!doctype html><html><head><meta charset="utf-8"><title>tunnel inspector</title>
            <style>
              body{font:14px/1.4 -apple-system,system-ui,sans-serif;margin:24px;color:#222}
              h1{font-size:18px} table{border-collapse:collapse;width:100%}
              th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #eee}
              .ok{color:#137333}.err{color:#c5221f}.muted{color:#888}
            </style></head><body>
            <h1>tunnel — request inspector <span class="muted">:4040</span></h1>
            <table><thead><tr><th>#</th><th>Method</th><th>Path</th><th>Status</th>
            <th>ms</th><th>req/resp bytes</th><th>trace</th></tr></thead>
            <tbody id="rows"></tbody></table>
            <script>
            async function tick(){
              const r = await fetch('/api/requests'); const data = await r.json();
              document.getElementById('rows').innerHTML = data.map(x =>
                `<tr><td>${x.id}</td><td>${x.method}</td><td>${x.path}</td>
                 <td class="${x.status>=200&&x.status<400?'ok':(x.status<0?'err':'')}">
                 ${x.status<0?('ERR '+(x.error||'')):x.status}</td>
                 <td>${x.durationMs<0?'…':x.durationMs}</td>
                 <td class="muted">${x.requestBytes} / ${x.responseBytes}</td>
                 <td class="muted">${x.trace}</td></tr>`).join('');
            }
            setInterval(tick, 1000); tick();
            </script></body></html>
            """;
}
