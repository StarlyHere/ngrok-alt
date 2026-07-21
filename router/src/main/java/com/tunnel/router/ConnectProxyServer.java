package com.tunnel.router;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Session-aware HTTP CONNECT proxy for gRPC and other raw TCP protocols. */
@Component
public class ConnectProxyServer {
    private static final Logger log = LoggerFactory.getLogger(ConnectProxyServer.class);
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final PodResolver resolver;
    private final int port;
    private final int relayPort;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket server;

    public ConnectProxyServer(PodResolver resolver,
                              @Value("${router.connect-port:8181}") int port,
                              @Value("${router.relay-tcp-port:8182}") int relayPort) {
        this.resolver = resolver;
        this.port = port;
        this.relayPort = relayPort;
    }

    @PostConstruct
    void start() throws IOException {
        server = new ServerSocket(port);
        workers.submit(this::acceptLoop);
        log.info("HTTP CONNECT proxy listening on {}", port);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!server.isClosed()) log.warn("CONNECT accept failed", e);
            }
        }
    }

    private void handle(Socket client) {
        Socket relay = null;
        try {
            client.setSoTimeout(10_000);
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            String headers = readHeaders(input);
            String[] lines = headers.split("\\r\\n");
            if (lines.length == 0 || !lines[0].startsWith("CONNECT ")) {
                reject(client, 405, "Method Not Allowed");
                return;
            }
            String sessionId = sessionId(lines);
            if (sessionId == null) {
                proxyAuthRequired(client);
                return;
            }
            PodResolver.PodTarget pod = resolver.targetForSession(sessionId).orElse(null);
            if (pod == null) {
                reject(client, 502, "Tunnel Not Found");
                return;
            }

            relay = new Socket();
            relay.connect(new InetSocketAddress(pod.host(), relayPort), 10_000);
            relay.setSoTimeout(10_000);
            DataOutputStream relayOutput = new DataOutputStream(relay.getOutputStream());
            relayOutput.writeUTF(sessionId);
            relayOutput.flush();
            if (new DataInputStream(relay.getInputStream()).readByte() != 0) {
                reject(client, 502, "Tunnel Not Found");
                return;
            }
            relay.setSoTimeout(0);

            client.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            client.setSoTimeout(0);
            Socket relaySocket = relay;
            Thread upstream = new Thread(
                    () -> copyAndClose(client, relaySocket, input, output(relaySocket)),
                    "connect-upstream");
            upstream.setDaemon(true);
            upstream.start();
            copy(relay.getInputStream(), client.getOutputStream());
        } catch (IOException e) {
            log.debug("CONNECT stream ended: {}", e.toString());
        } finally {
            close(client);
            close(relay);
        }
    }

    private static String readHeaders(InputStream input) throws IOException {
        byte[] bytes = new byte[MAX_HEADER_BYTES];
        int count = 0;
        while (count < bytes.length) {
            int value = input.read();
            if (value < 0) throw new IOException("CONNECT request ended before headers");
            bytes[count++] = (byte) value;
            if (count >= 4 && bytes[count - 4] == '\r' && bytes[count - 3] == '\n'
                    && bytes[count - 2] == '\r' && bytes[count - 1] == '\n') {
                return new String(bytes, 0, count, StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("CONNECT headers exceed limit");
    }

    static String sessionId(String[] lines) {
        for (String line : lines) {
            if (!line.toLowerCase(Locale.ROOT).startsWith("proxy-authorization: basic ")) continue;
            try {
                String encoded = line.substring(line.indexOf(':') + 1).trim().substring(6).trim();
                String credentials = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                String session = credentials.substring(0, credentials.indexOf(':'));
                return session.matches("[A-Za-z0-9._-]+") ? session : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void reject(Socket socket, int status, String reason) throws IOException {
        socket.getOutputStream().write(("HTTP/1.1 " + status + " " + reason
                + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static void proxyAuthRequired(Socket socket) throws IOException {
        socket.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                + "Proxy-Authenticate: Basic realm=\"spr-local-connect\"\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static OutputStream output(Socket socket) {
        try { return socket.getOutputStream(); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void copyAndClose(Socket first, Socket second, InputStream input, OutputStream output) {
        try { copy(input, output); } catch (IOException ignored) {} finally { close(first); close(second); }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        input.transferTo(output);
        output.flush();
    }

    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }

    @PreDestroy
    void stop() throws IOException {
        if (server != null) server.close();
        workers.shutdownNow();
    }
}
