package com.tunnel.protocol.mux;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.transport.TunnelConnection;
import com.tunnel.protocol.transport.TunnelStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Application-level stream multiplexer over a single {@link FrameChannel}
 * (PRD-v2.md §4 #1, §8). This is the demux/remux core: many concurrent
 * {@link MuxStream}s share one underlying connection.
 *
 * <p>Wiring: the transport binding pushes every inbound frame into
 * {@link #onFrame(Frame)} and provides a {@link FrameChannel} for outbound
 * frames. Stream frames are routed to/from {@link MuxStream}s; control frames
 * (REGISTER/HEARTBEAT/…) are handed to the supplied control handler.
 *
 * <p>Stream-id allocation avoids collisions between the two peers by parity:
 * one side opens odd ids, the other even (PRD-v2.md §15 mux choice).
 */
public final class MuxConnection implements TunnelConnection {

    private final FrameChannel channel;
    private final Consumer<Frame> controlHandler;
    private final Map<Integer, MuxStream> streams = new ConcurrentHashMap<>();
    private final BlockingQueue<MuxStream> accepted = new LinkedBlockingQueue<>();
    private final AtomicInteger nextId;
    private final AtomicBoolean open = new AtomicBoolean(true);

    /** Sentinel queued on close to wake a blocked {@link #acceptStream()}. */
    private final MuxStream closeSentinel = new MuxStream(Integer.MIN_VALUE, this);

    /**
     * @param channel        outbound frame sink
     * @param localInitiator if true this side opens odd stream ids (1,3,5…),
     *                       else even (2,4,6…); the two peers must disagree
     * @param controlHandler receives REGISTER/REGISTER_ACK/HEARTBEAT/CLOSE_SESSION/
     *                       ERROR frames for the owner to interpret
     */
    public MuxConnection(FrameChannel channel, boolean localInitiator, Consumer<Frame> controlHandler) {
        this.channel = channel;
        this.controlHandler = controlHandler;
        this.nextId = new AtomicInteger(localInitiator ? 1 : 2);
    }

    FrameChannel channel() {
        return channel;
    }

    @Override
    public TunnelStream openStream() throws IOException {
        if (!open.get()) {
            throw new IOException("connection closed");
        }
        int id = nextId.getAndAdd(2);
        MuxStream stream = new MuxStream(id, this);
        streams.put(id, stream);
        channel.send(Frame.open(id));
        return stream;
    }

    @Override
    public TunnelStream acceptStream() {
        try {
            MuxStream s = accepted.take();
            if (s == closeSentinel) {
                accepted.add(closeSentinel); // keep any other waiters released too
                return null;
            }
            return s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Feed one inbound frame from the transport. Never throws to the caller's I/O loop. */
    public void onFrame(Frame frame) {
        if (frame.type().isControl()) {
            controlHandler.accept(frame);
            return;
        }
        switch (frame.type()) {
            case OPEN_STREAM -> handleOpen(frame.streamId());
            case DATA -> {
                MuxStream s = streams.get(frame.streamId());
                if (s != null) {
                    s.deliver(frame.payload());
                }
            }
            case CLOSE_STREAM -> {
                MuxStream s = streams.get(frame.streamId());
                if (s != null) {
                    s.remoteClosed();
                }
            }
            case RESET_STREAM -> {
                MuxStream s = streams.remove(frame.streamId());
                if (s != null) {
                    s.remoteReset();
                }
            }
            default -> { /* unreachable: control handled above */ }
        }
    }

    private void handleOpen(int id) {
        MuxStream stream = streams.computeIfAbsent(id, k -> new MuxStream(id, this));
        accepted.add(stream);
    }

    void onStreamClosed(int id) {
        streams.remove(id);
    }

    /** Send a control frame (REGISTER/HEARTBEAT/CLOSE_SESSION/…). */
    public void sendControl(FrameType type, byte[] payload) throws IOException {
        channel.send(Frame.control(type, payload));
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            for (MuxStream s : streams.values()) {
                s.remoteClosed();
            }
            streams.clear();
            accepted.add(closeSentinel);
        }
    }
}
