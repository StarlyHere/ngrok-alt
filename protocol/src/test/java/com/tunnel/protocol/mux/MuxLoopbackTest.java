package com.tunnel.protocol.mux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tunnel.protocol.codec.HttpMessageCodec;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import com.tunnel.protocol.transport.TunnelStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Wires two {@link MuxConnection}s back-to-back over in-memory channels and
 * pushes a request/response exchange — and several concurrent ones — through a
 * stream. Exercises the demux/remux core plus {@link HttpMessageCodec} without
 * any transport.
 */
class MuxLoopbackTest {

    @Test
    void singleRequestResponseRoundTrips() throws Exception {
        Harness h = new Harness();
        try {
            // Acceptor side echoes the request path back in the response body.
            h.serveOnce(req -> new HttpResponseMessage(
                    200,
                    Map.of("Content-Type", List.of("text/plain")),
                    ("served:" + req.path()).getBytes(StandardCharsets.UTF_8)));

            TunnelStream stream = h.opener.openStream();
            HttpMessageCodec.writeRequest(stream.output(),
                    new HttpRequestMessage("GET", "/hello", "name=Ada", Map.of(), new byte[0]));
            HttpResponseMessage resp = HttpMessageCodec.readResponse(stream.input());
            stream.close();

            assertEquals(200, resp.status());
            assertEquals("served:/hello", new String(resp.body(), StandardCharsets.UTF_8));
        } finally {
            h.shutdown();
        }
    }

    @Test
    void concurrentStreamsAreIndependent() throws Exception {
        Harness h = new Harness();
        try {
            h.serveMany(req -> new HttpResponseMessage(
                    200, Map.of(), ("echo:" + req.path()).getBytes(StandardCharsets.UTF_8)));

            ExecutorService clients = Executors.newFixedThreadPool(8);
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) {
                final String path = "/req-" + i;
                futures.add(clients.submit(() -> {
                    TunnelStream s = h.opener.openStream();
                    HttpMessageCodec.writeRequest(s.output(),
                            new HttpRequestMessage("GET", path, "", Map.of(), new byte[0]));
                    HttpResponseMessage r = HttpMessageCodec.readResponse(s.input());
                    s.close();
                    return new String(r.body(), StandardCharsets.UTF_8);
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals("echo:/req-" + i, futures.get(i).get(5, TimeUnit.SECONDS));
            }
            clients.shutdownNow();
        } finally {
            h.shutdown();
        }
    }

    @Test
    void acceptStreamReturnsNullAfterClose() throws Exception {
        Harness h = new Harness();
        h.acceptor.close();
        // A blocked acceptStream is released with null once the connection closes.
        assertEquals(null, h.acceptor.acceptStream());
        h.shutdown();
    }

    /** Two mux connections joined by direct in-memory frame delivery. */
    private static final class Harness {
        final MuxConnection opener;
        final MuxConnection acceptor;
        final ExecutorService pool = Executors.newCachedThreadPool();

        Harness() {
            // Lazily-linked channels: opener sends to acceptor and vice versa.
            MuxConnection[] box = new MuxConnection[2];
            opener = new MuxConnection(frame -> box[1].onFrame(frame), true, c -> { });
            acceptor = new MuxConnection(frame -> box[0].onFrame(frame), false, c -> { });
            box[0] = opener;
            box[1] = acceptor;
        }

        interface Handler {
            HttpResponseMessage handle(HttpRequestMessage req) throws Exception;
        }

        void serveOnce(Handler handler) {
            pool.submit(() -> {
                TunnelStream s = acceptor.acceptStream();
                assertNotNull(s);
                serve(s, handler);
                return null;
            });
        }

        void serveMany(Handler handler) {
            pool.submit(() -> {
                TunnelStream s;
                while ((s = acceptor.acceptStream()) != null) {
                    final TunnelStream stream = s;
                    pool.submit(() -> {
                        serve(stream, handler);
                        return null;
                    });
                }
                return null;
            });
        }

        private void serve(TunnelStream s, Handler handler) {
            try {
                HttpRequestMessage req = HttpMessageCodec.readRequest(s.input());
                HttpMessageCodec.writeResponse(s.output(), handler.handle(req));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void shutdown() {
            opener.close();
            acceptor.close();
            pool.shutdownNow();
        }
    }
}
