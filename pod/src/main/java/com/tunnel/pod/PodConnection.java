package com.tunnel.pod;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.codec.ControlCodec;
import com.tunnel.protocol.codec.HttpMessageCodec;
import com.tunnel.protocol.dto.CloseMessage;
import com.tunnel.protocol.dto.HeartbeatMessage;
import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import com.tunnel.protocol.dto.RegisterAck;
import com.tunnel.protocol.dto.RegisterMessage;
import com.tunnel.protocol.dto.Session;
import com.tunnel.protocol.dto.SessionStatus;
import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameCodec;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.mux.FrameChannel;
import com.tunnel.protocol.mux.MuxConnection;
import com.tunnel.protocol.transport.TunnelStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client's connection as seen by the pod. Wraps a transport-agnostic
 * {@link FrameChannel} with a {@link MuxConnection}, handles the control
 * protocol (register / heartbeat / close), and forwards inbound HTTP requests
 * down a fresh stream (PRD-v2.md §4 #1; BUILD-CHECKLIST.md Part 2).
 */
public class PodConnection {

    private static final Logger log = LoggerFactory.getLogger(PodConnection.class);

    private final FrameChannel sender;
    private final Runnable closer;
    private final ConnectionRegistry registry;
    private final PodProperties props;
    private final PodRedisService redis;
    /** Owner authenticated at the transport handshake (§9.2.2); the ownership boundary. */
    private final String authedOwner;
    private final MuxConnection mux;
    private final AtomicLong lastHeartbeatAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean counted = new AtomicBoolean();
    private final AtomicInteger inFlightStreams = new AtomicInteger();

    private volatile String sessionId;
    private volatile String ownerId;
    private volatile int targetPort;

    public PodConnection(FrameChannel sender, Runnable closer,
                         ConnectionRegistry registry, PodProperties props,
                         PodRedisService redis, String authedOwner) {
        this.sender = sender;
        this.closer = closer;
        this.registry = registry;
        this.props = props;
        this.redis = redis;
        this.authedOwner = authedOwner;
        // Pod is the stream initiator (odd ids); it opens a stream per inbound request.
        this.mux = new MuxConnection(this::sendFrame, true, this::onControl);
    }

    // --- transport plumbing ---

    /** Feed one inbound frame (already decoded from the wire) to the mux. */
    void onFrame(Frame frame) {
        mux.onFrame(frame);
    }

    /** Feed an inbound WebSocket binary message (one encoded frame) to the mux. */
    public void onBinary(byte[] frameBytes) {
        mux.onFrame(FrameCodec.decode(frameBytes));
    }

    private void sendFrame(Frame frame) throws IOException {
        sender.send(frame);
    }

    // --- control protocol ---

    private void onControl(Frame frame) {
        try {
            switch (frame.type()) {
                case REGISTER -> handleRegister(ControlCodec.decodeRegister(frame.payload()));
                case HEARTBEAT -> handleHeartbeat(ControlCodec.decodeHeartbeat(frame.payload()));
                case CLOSE_SESSION -> handleClose(ControlCodec.decodeClose(frame.payload()));
                default -> log.warn("ignoring unexpected control frame {}", frame.type());
            }
        } catch (Exception e) {
            log.warn("failed handling control frame {}: {}", frame.type(), e.toString());
        }
    }

    private void handleRegister(RegisterMessage msg) throws IOException {
        // Ownership boundary (§9.2.3): the session's owner (set by the Coordinator)
        // must match the token the transport handshake was authenticated with.
        String sessionOwner = redis.sessionOwner(msg.sessionId()).orElse(null);
        if (sessionOwner != null && !sessionOwner.equals(authedOwner)) {
            log.warn("owner mismatch on register: token owner '{}' != session owner '{}' for {}",
                    authedOwner, sessionOwner, Session.redact(msg.sessionId()));
            mux.sendControl(FrameType.REGISTER_ACK,
                    ControlCodec.encodeRegisterAck(RegisterAck.rejected(ErrorCodes.OWNER_MISMATCH)));
            close();
            return;
        }

        this.sessionId = msg.sessionId();
        this.ownerId = authedOwner;
        this.targetPort = msg.targetPort();
        registry.register(sessionId, this);

        // Take ownership in Redis: mark ACTIVE on this pod, refresh TTL, count it.
        redis.activateSession(sessionId);
        if (counted.compareAndSet(false, true)) {
            redis.incrementConns();
        }

        Session session = new Session(
                sessionId, deriveSubdomain(sessionId), ownerId, props.id(),
                SessionStatus.ACTIVE, System.currentTimeMillis());
        RegisterAck ack = RegisterAck.ok(session, props.id(), props.heartbeatIntervalMs());
        mux.sendControl(FrameType.REGISTER_ACK, ControlCodec.encodeRegisterAck(ack));
        log.info("session {} registered on {} (owner '{}') -> 127.0.0.1:{}",
                Session.redact(sessionId), props.id(), ownerId, targetPort);
    }

    private void handleHeartbeat(HeartbeatMessage msg) {
        lastHeartbeatAt.set(System.currentTimeMillis());
        if (sessionId != null) {
            redis.refreshSessionTtl(sessionId);
        }
        log.debug("heartbeat from session {}", Session.redact(msg.sessionId()));
    }

    private void handleClose(CloseMessage msg) {
        log.info("session {} requested close: {}", Session.redact(msg.sessionId()), msg.reason());
        close();
    }

    /**
     * Graceful drain (PRD-v2.md §10): tell the client to reconnect, then close.
     * Used on SIGTERM so a rolling deploy doesn't drop requests.
     */
    public void drain() {
        try {
            if (sessionId != null) {
                mux.sendControl(FrameType.CLOSE_SESSION,
                        ControlCodec.encodeClose(new CloseMessage(sessionId, ownerId, "drain")));
            }
        } catch (IOException ignored) {
            // transport already going away
        }
        close();
    }

    // --- request forwarding (the reverse data path) ---

    /**
     * Open a stream, send the request to the client, and block for its response.
     * Called concurrently by the forwarding controller (one stream per request).
     */
    public HttpResponseMessage forward(HttpRequestMessage request) throws IOException {
        if (inFlightStreams.incrementAndGet() > props.maxStreamsPerSession()) {
            inFlightStreams.decrementAndGet();
            log.warn("session {} exceeded max {} concurrent streams",
                    Session.redact(sessionId), props.maxStreamsPerSession());
            return new HttpResponseMessage(429, Map.of(),
                    ("{\"error\":\"" + ErrorCodes.QUOTA_EXCEEDED + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        TunnelStream stream = mux.openStream();
        try {
            HttpMessageCodec.writeRequest(stream.output(), request);
            return HttpMessageCodec.readResponse(stream.input());
        } finally {
            stream.close();
            inFlightStreams.decrementAndGet();
        }
    }

    public void close() {
        mux.close();
        registry.unregister(sessionId);
        if (counted.compareAndSet(true, false)) {
            redis.decrementConns();
        }
        closer.run();
    }

    // --- accessors ---

    public String sessionId() { return sessionId; }
    public String ownerId() { return ownerId; }
    public int targetPort() { return targetPort; }
    public long lastHeartbeatAt() { return lastHeartbeatAt.get(); }

    private static String deriveSubdomain(String sessionId) {
        return "s-" + (sessionId == null ? "unknown" : sessionId.substring(0, Math.min(8, sessionId.length())));
    }
}
