package com.tunnel.client;

import com.tunnel.protocol.TunnelConstants;
import com.tunnel.protocol.codec.ControlCodec;
import com.tunnel.protocol.codec.HttpMessageCodec;
import com.tunnel.protocol.dto.AssignmentRequest;
import com.tunnel.protocol.dto.AssignmentResponse;
import com.tunnel.protocol.dto.HeartbeatMessage;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import com.tunnel.protocol.dto.RegisterAck;
import com.tunnel.protocol.dto.RegisterMessage;
import com.tunnel.protocol.dto.Session;
import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.transport.TunnelConnection;
import com.tunnel.protocol.transport.TunnelProvider;
import com.tunnel.protocol.transport.TunnelStream;
import com.tunnel.client.inspect.RequestInspector;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one tunnel session over its lifetime (PRD-v2.md §7): connect outbound,
 * register, serve inbound requests by forwarding them to the local service,
 * heartbeat to keep ownership alive, and auto-reconnect with backoff when the
 * connection drops. The session id is stable, so a reconnect resumes on the same
 * URL (PRD-v2.md §10).
 */
public class TunnelClient {

    private static final Logger log = LoggerFactory.getLogger(TunnelClient.class);

    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final long REGISTER_TIMEOUT_MS = 10_000;

    private final TunnelConfig config;
    private final TunnelProvider provider;
    private final CoordinatorClient coordinator;
    private final LocalForwarder forwarder;
    private final RequestInspector inspector;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tunnel-stream");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tunnel-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;
    private volatile TunnelConnection current;
    private volatile Thread runThread;
    /** Stable across reconnects → same URL. Issued by the Coordinator. */
    private volatile String sessionId;

    public TunnelClient(TunnelConfig config, TunnelProvider provider) {
        this.config = config;
        this.provider = provider;
        this.forwarder = new LocalForwarder(config.targetHost(), config.targetPort());
        this.inspector = new RequestInspector(config.inspectorPort());
        this.coordinator = config.directMode() ? null
                : new CoordinatorClient(config.coordinatorEndpoint(), config.token());
        this.sessionId = config.sessionId();
    }

    /** Blocking run loop: assign, connect, serve, and reconnect until {@link #shutdown()}. */
    public void run() throws IOException {
        runThread = Thread.currentThread();
        inspector.start();
        long backoff = INITIAL_BACKOFF_MS;
        while (running) {
            try {
                AssignmentResponse assignment = obtainAssignment();
                connectAndServe(assignment);
                backoff = INITIAL_BACKOFF_MS; // a healthy cycle resets backoff
            } catch (Exception e) {
                log.warn("tunnel connection failed: {}", e.toString());
            }
            if (!running) {
                break;
            }
            sleepBackoff(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
        inspector.stop();
        log.info("tunnel client stopped");
    }
    

    /**
     * Ask the Coordinator which pod to connect to (path A). On reconnect the
     * current session id is sent back so the Coordinator reuses it (stable URL)
     * and only re-picks a live pod. In direct mode the assignment is synthesized.
     */
    private AssignmentResponse obtainAssignment() throws IOException, InterruptedException {
        if (config.directMode()) {
            if (sessionId == null) {
                sessionId = newSessionId();
            }
            return AssignmentResponse.ok(sessionId, null, "direct",
                    config.directPodEndpoint().toString(), config.heartbeatIntervalMs());
        }
        log.info("requesting assignment from coordinator {} …", config.coordinatorEndpoint());
        AssignmentResponse a = coordinator.obtain(
                new AssignmentRequest(config.ownerId(), config.targetPort(), sessionId));
        if (!a.accepted()) {
            throw new IOException("assignment rejected: " + a.errorCode());
        }
        sessionId = a.sessionId(); // remember for the next (re)connect
        return a;

    }

    private void connectAndServe(AssignmentResponse assignment) throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put(TunnelConstants.HEADER_SESSION, assignment.sessionId());
        if (config.token() != null && !config.token().isBlank()) {
            headers.put(TunnelConstants.HEADER_AUTHORIZATION, TunnelConstants.BEARER_PREFIX + config.token());
        }

        URI podEndpoint = URI.create(assignment.podWsUrl());
        log.info("connecting to {} ({}) …", podEndpoint, assignment.podId());
        TunnelConnection conn = provider.open(podEndpoint, headers);
        current = conn;

        CountDownLatch ackLatch = new CountDownLatch(1);
        AtomicReference<RegisterAck> ackRef = new AtomicReference<>();
        conn.setControlHandler(frame -> onControl(frame, conn, ackLatch, ackRef));

        RegisterMessage reg = new RegisterMessage(
                assignment.sessionId(), config.ownerId(), config.targetPort(),
                provider.name(), config.clientVersion());
    
        conn.sendControl(FrameType.REGISTER, ControlCodec.encodeRegister(reg));

        if (!ackLatch.await(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            conn.close();
            throw new IOException("registration timed out");
        }
        RegisterAck ack = ackRef.get();
        if (ack == null || !ack.accepted()) {
            conn.close();
            throw new IOException("registration rejected: " + (ack == null ? "no ack" : ack.errorCode()));
        }
        printConnected(assignment);

        long interval = ack.heartbeatIntervalMs() > 0 ? ack.heartbeatIntervalMs() : config.heartbeatIntervalMs();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(conn), interval, interval, TimeUnit.MILLISECONDS);
        try {
            TunnelStream stream;
            while ((stream = conn.acceptStream()) != null) {
                final TunnelStream s = stream;
                streamExecutor.submit(() -> handle(s));
            }
        } finally {
            heartbeat.cancel(true);
            conn.close();
            log.info("disconnected from {}", podEndpoint);
        }
    }

    private void onControl(Frame frame, TunnelConnection conn,
                           CountDownLatch ackLatch, AtomicReference<RegisterAck> ackRef) {
        switch (frame.type()) {
            case REGISTER_ACK -> {
                ackRef.set(ControlCodec.decodeRegisterAck(frame.payload()));
                ackLatch.countDown();
            }
            case CLOSE_SESSION -> {
                log.info("pod requested session close; will reconnect");
                conn.close();
            }
            case ERROR -> log.warn("pod reported error frame");
            default -> { /* HEARTBEAT from pod, etc.: ignore */ }
        }
    }

    private void handle(TunnelStream stream) {
        RequestInspector.Record record = null;
        try {
            HttpRequestMessage req = HttpMessageCodec.readRequest(stream.input());
            String trace = firstHeader(req.headers(), TunnelConstants.HEADER_TRACE);
            record = inspector.begin(req.method(), req.path(), trace, req.body().length);
            try {
                HttpResponseMessage resp = forwarder.forward(req);
                HttpMessageCodec.writeResponse(stream.output(), resp);
                inspector.complete(record, resp.status(), resp.body().length);
            } catch (Exception e) {
                log.warn("local forward failed for {} {}: {}", req.method(), req.path(), e.toString());
                if (record != null) {
                    inspector.fail(record, e.getMessage());
                }
                writeQuietly(stream, new HttpResponseMessage(502, Map.of(),
                        ("local forward failed: " + e.getMessage()).getBytes()));
            }
        } catch (IOException e) {
            log.debug("stream read ended: {}", e.toString());
        } finally {
            stream.close();
        }
    }

    private void sendHeartbeat(TunnelConnection conn) {
        try {
            HeartbeatMessage hb = new HeartbeatMessage(
                    sessionId, config.ownerId(), System.currentTimeMillis());
            conn.sendControl(FrameType.HEARTBEAT, ControlCodec.encodeHeartbeat(hb));
        } catch (IOException e) {
            log.debug("heartbeat send failed (connection closing): {}", e.toString());
        }
    }

    private void writeQuietly(TunnelStream stream, HttpResponseMessage resp) {
        try {
            HttpMessageCodec.writeResponse(stream.output(), resp);
        } catch (IOException ignored) {
            // peer likely gone
        }
    }

    private void printConnected(AssignmentResponse assignment) {
        String subdomain = assignment.subdomain() != null ? assignment.subdomain() : sessionId;
        log.info("");
        log.info("  tunnel established");
        log.info("    → https://{}{}   (session {}, {})",
                subdomain, TunnelConstants.TUNNEL_DOMAIN_SUFFIX,
                Session.redact(sessionId), assignment.podId());
        log.info("    → forwarding to 127.0.0.1:{}", config.targetPort());
        log.info("    → inspect requests at http://localhost:{}", config.inspectorPort());
        log.info("");
    }

    private static String newSessionId() {
        byte[] bytes = new byte[TunnelConstants.SESSION_ID_BITS / 8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private void sleepBackoff(long backoffMs) {
        try {
            log.info("reconnecting in {} ms …", backoffMs);
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            // Only a real shutdown should stop the loop; a stray interrupt just
            // cuts the wait short and we retry immediately.
            if (!running) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void shutdown() {
        running = false;
        TunnelConnection conn = current;
        if (conn != null) {
            conn.close();
        }
        scheduler.shutdownNow();
        streamExecutor.shutdownNow();
        inspector.stop();
        Thread t = runThread;
        if (t != null) {
            t.interrupt();
        }
    }
}
