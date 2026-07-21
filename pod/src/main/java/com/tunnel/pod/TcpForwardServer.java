package com.tunnel.pod;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Cluster-internal bridge from the CONNECT router to the session's mux connection. */
@Component
public class TcpForwardServer {
    private static final Logger log = LoggerFactory.getLogger(TcpForwardServer.class);
    private final ConnectionRegistry registry;
    private final PodProperties properties;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket server;

    public TcpForwardServer(ConnectionRegistry registry, PodProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    void start() throws IOException {
        server = new ServerSocket(properties.tcpPort());
        workers.submit(this::acceptLoop);
        log.info("raw TCP bridge listening on {}", properties.tcpPort());
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!server.isClosed()) log.warn("TCP bridge accept failed", e);
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            String sessionId = new DataInputStream(socket.getInputStream()).readUTF();
            PodConnection connection = registry.find(sessionId).orElse(null);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeByte(connection == null ? 1 : 0);
            output.flush();
            if (connection == null) {
                socket.close();
                return;
            }
            socket.setSoTimeout(0);
            connection.forwardTcp(socket);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    @PreDestroy
    void stop() throws IOException {
        if (server != null) server.close();
        workers.shutdownNow();
    }
}
