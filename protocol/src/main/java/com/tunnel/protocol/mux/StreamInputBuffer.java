package com.tunnel.protocol.mux;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A blocking {@link InputStream} fed asynchronously by inbound
 * {@link com.tunnel.protocol.frame.FrameType#DATA DATA} frames for a single
 * {@link MuxStream}. The mux read loop calls {@link #deliver}/{@link #close}
 * from the transport thread; the stream consumer reads on its own thread and
 * blocks until bytes (or end-of-stream) arrive.
 */
final class StreamInputBuffer extends InputStream {

    /** Identity sentinel signalling clean end-of-stream. */
    private static final byte[] EOF = new byte[0];

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private byte[] current;
    private int pos;
    private boolean ended;
    private volatile IOException error;

    /** Enqueue received bytes (no-op for empty arrays). */
    void deliver(byte[] data) {
        if (data != null && data.length > 0) {
            queue.add(data);
        }
    }

    /** Signal clean end-of-stream (e.g. CLOSE_STREAM frame). */
    void signalEof() {
        queue.add(EOF);
    }

    /** Signal abrupt end-of-stream with an error (e.g. RESET_STREAM). */
    void signalError(IOException e) {
        this.error = e;
        queue.add(EOF);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensureCurrent()) {
            return -1;
        }
        int n = Math.min(len, current.length - pos);
        System.arraycopy(current, pos, b, off, n);
        pos += n;
        return n;
    }

    /** Ensure {@link #current} has unread bytes; returns false at end-of-stream. */
    private boolean ensureCurrent() throws IOException {
        while (current == null || pos >= current.length) {
            if (ended) {
                return false;
            }
            byte[] next;
            try {
                next = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while reading stream");
            }
            if (next == EOF) {
                ended = true;
                if (error != null) {
                    throw error;
                }
                return false;
            }
            current = next;
            pos = 0;
        }
        return true;
    }
}
