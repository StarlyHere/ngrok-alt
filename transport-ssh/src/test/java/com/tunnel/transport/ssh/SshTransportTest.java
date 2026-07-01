package com.tunnel.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tunnel.protocol.codec.HttpMessageCodec;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import com.tunnel.protocol.transport.TunnelConnection;
import com.tunnel.protocol.transport.TunnelStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The same end-to-end request/response exchange as {@code MuxLoopbackTest}, but
 * driven over a real SSH connection (PRD-v2.md §8). Proves the
 * {@link com.tunnel.protocol.transport.TunnelProvider} abstraction: identical mux
 * + {@link HttpMessageCodec}, different byte transport.
 */
class SshTransportTest {

    @Test
    void httpExchangeRoundTripsOverSsh() throws Exception {
        try (Harness h = new Harness()) {
            // Client side (acceptor = the developer's machine): serve each request.
            h.serve(req -> new HttpResponseMessage(200, Map.of(),
                    ("served:" + req.path()).getBytes(StandardCharsets.UTF_8)));

            // Pod side opens a stream and sends a request (the inbound QA6 request).
            TunnelConnection pod = h.awaitPodSide();
            TunnelStream stream = pod.openStream();
            HttpMessageCodec.writeRequest(stream.output(),
                    new HttpRequestMessage("GET", "/hello", "name=Ada", Map.of(), new byte[0]));
            HttpResponseMessage resp = HttpMessageCodec.readResponse(stream.input());
            stream.close();

            assertEquals(200, resp.status());
            assertEquals("served:/hello", new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void concurrentStreamsOverSsh() throws Exception {
        try (Harness h = new Harness()) {
            h.serve(req -> new HttpResponseMessage(200, Map.of(),
                    ("echo:" + req.path()).getBytes(StandardCharsets.UTF_8)));

            TunnelConnection pod = h.awaitPodSide();
            ExecutorService callers = Executors.newFixedThreadPool(8);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final String path = "/req-" + i;
                futures.add(callers.submit(() -> {
                    TunnelStream s = pod.openStream();
                    HttpMessageCodec.writeRequest(s.output(),
                            new HttpRequestMessage("GET", path, "", Map.of(), new byte[0]));
                    HttpResponseMessage r = HttpMessageCodec.readResponse(s.input());
                    s.close();
                    return new String(r.body(), StandardCharsets.UTF_8);
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals("echo:/req-" + i, futures.get(i).get(10, TimeUnit.SECONDS));
            }
            callers.shutdownNow();
        }
    }

    @Test
    void reportsRoundTripLatency() throws Exception {
        try (Harness h = new Harness()) {
            h.serve(req -> new HttpResponseMessage(200, Map.of(), new byte[0]));
            TunnelConnection pod = h.awaitPodSide();

            int n = 50;
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                TunnelStream s = pod.openStream();
                HttpMessageCodec.writeRequest(s.output(),
                        new HttpRequestMessage("GET", "/ping", "", Map.of(), new byte[0]));
                HttpMessageCodec.readResponse(s.input());
                s.close();
            }
            long avgMicros = (System.nanoTime() - start) / n / 1_000;
            System.out.println("[ssh] avg round-trip over loopback: " + avgMicros + " µs (" + n + " reqs)");
            assertTrue(avgMicros >= 0);
        }
    }

    /** SSH server + client wired so the pod side opens streams and the client serves them. */
    private static final class Harness implements AutoCloseable {
        private final SshTunnelServer server;
        private final TunnelConnection clientSide;
        private final AtomicReference<TunnelConnection> podSide = new AtomicReference<>();
        private final CountDownLatch podReady = new CountDownLatch(1);
        private final ExecutorService pool = Executors.newCachedThreadPool();

        interface Handler {
            HttpResponseMessage handle(HttpRequestMessage req) throws Exception;
        }

        Harness() throws Exception {
            this.server = new SshTunnelServer(0, conn -> {
                podSide.set(conn);
                podReady.countDown();
            });
            this.clientSide = new SshTunnelProvider()
                    .open(URI.create("ssh://localhost:" + server.port()), Map.of());
        }

        void serve(Handler handler) {
            pool.submit(() -> {
                TunnelStream s;
                while ((s = clientSide.acceptStream()) != null) {
                    final TunnelStream stream = s;
                    pool.submit(() -> {
                        try {
                            HttpRequestMessage req = HttpMessageCodec.readRequest(stream.input());
                            HttpMessageCodec.writeResponse(stream.output(), handler.handle(req));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                return null;
            });
        }

        TunnelConnection awaitPodSide() throws InterruptedException {
            assertTrue(podReady.await(15, TimeUnit.SECONDS), "pod side never connected");
            TunnelConnection pod = podSide.get();
            assertNotNull(pod);
            return pod;
        }

        @Override
        public void close() throws Exception {
            pool.shutdownNow();
            clientSide.close();
            server.close();
        }
    }
}
