package com.tunnel.protocol.mux;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.transport.TunnelStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One multiplexed stream over a {@link MuxConnection}. Inbound bytes arrive as
 * DATA frames and surface through {@link #input()}; bytes written to
 * {@link #output()} are wrapped into DATA frames and sent on the shared channel.
 */
final class MuxStream implements TunnelStream {

    private final int id;
    private final MuxConnection connection;
    private final StreamInputBuffer in = new StreamInputBuffer();
    private final OutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean();

    MuxStream(int id, MuxConnection connection) {
        this.id = id;
        this.connection = connection;
        this.out = new FrameOutputStream();
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return out;
    }

    /** Feed received payload bytes to the consumer side. */
    void deliver(byte[] payload) {
        in.deliver(payload);
    }

    /** Remote signalled graceful end of this stream. */
    void remoteClosed() {
        in.signalEof();
    }

    /** Remote signalled abrupt reset of this stream. */
    void remoteReset() {
        in.signalError(new IOException("stream " + id + " reset by peer"));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            in.signalEof();
            connection.onStreamClosed(id);
            try {
                connection.channel().send(Frame.close(id));
            } catch (IOException e) {
                // Connection is going away; nothing useful to do here.
            }
        }
    }

    /** OutputStream that frames each write as a DATA frame on the shared channel. */
    private final class FrameOutputStream extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed.get()) {
                throw new IOException("stream " + id + " is closed");
            }
            if (len == 0) {
                return;
            }
            connection.channel().send(Frame.data(id, Arrays.copyOfRange(b, off, off + len)));
        }

        @Override
        public void close() {
            // Closing the OutputStream closes the whole stream.
            MuxStream.this.close();
        }
    }

    /** Convenience for call sites that don't want a checked exception. */
    void writeUnchecked(byte[] data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
