package com.tunnel.transport.ssh;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameCodec;
import com.tunnel.protocol.mux.MuxConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Runs the shared {@link MuxConnection} over a plain byte pipe (an
 * {@link InputStream}/{@link OutputStream} pair) using the {@code protocol}
 * frame codec. This is the heart of the transport abstraction (PRD-v2.md §8):
 * the WebSocket transport frames each mux frame as a binary <em>message</em>;
 * here the very same frames are length-delimited on a byte <em>stream</em> (an
 * SSH channel). Nothing above the byte pipe changes.
 */
public final class FramedByteTransport implements AutoCloseable {

    private final MuxConnection mux;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final Thread reader;
    private volatile boolean closed;

    public FramedByteTransport(InputStream in, OutputStream out,
                               boolean localInitiator, Consumer<Frame> controlHandler) {
        this.out = out;
        this.mux = new MuxConnection(this::send, localInitiator, controlHandler);
        this.reader = new Thread(() -> readLoop(in), "framed-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** The multiplexed connection carried over this byte pipe. */
    public MuxConnection connection() {
        return mux;
    }

    private void send(Frame frame) throws IOException {
        synchronized (writeLock) {
            FrameCodec.write(out, frame);
            out.flush();
        }
    }

    private void readLoop(InputStream in) {
        try {
            Frame frame;
            while (!closed && (frame = FrameCodec.read(in)) != null) {
                mux.onFrame(frame);
            }
        } catch (IOException e) {
            // pipe closed — fall through to close the mux
        } finally {
            mux.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        mux.close();
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
        reader.interrupt();
    }
}
