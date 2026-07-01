package com.tunnel.protocol.codec;

import com.tunnel.protocol.dto.CloseMessage;
import com.tunnel.protocol.dto.HeartbeatMessage;
import com.tunnel.protocol.dto.RegisterAck;
import com.tunnel.protocol.dto.RegisterMessage;
import com.tunnel.protocol.dto.Session;
import com.tunnel.protocol.dto.SessionStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Serializes the control DTOs (REGISTER / REGISTER_ACK / HEARTBEAT /
 * CLOSE_SESSION) to and from the {@code byte[]} payload carried inside a control
 * {@link com.tunnel.protocol.frame.Frame}. Same dependency-free DataStream
 * approach as {@link HttpMessageCodec}.
 */
public final class ControlCodec {

    private ControlCodec() {
    }

    // --- RegisterMessage ---

    public static byte[] encodeRegister(RegisterMessage m) {
        return write(out -> {
            out.writeUTF(m.sessionId());
            out.writeUTF(nz(m.ownerId()));
            out.writeInt(m.targetPort());
            out.writeUTF(nz(m.transport()));
            out.writeUTF(nz(m.clientVersion()));
        });
    }

    public static RegisterMessage decodeRegister(byte[] bytes) {
        return read(bytes, in -> new RegisterMessage(
                in.readUTF(), in.readUTF(), in.readInt(), in.readUTF(), in.readUTF()));
    }

    // --- RegisterAck ---

    public static byte[] encodeRegisterAck(RegisterAck a) {
        return write(out -> {
            out.writeBoolean(a.accepted());
            out.writeUTF(nz(a.errorCode()));
            out.writeUTF(nz(a.podId()));
            out.writeLong(a.heartbeatIntervalMs());
            writeSession(out, a.session());
        });
    }

    public static RegisterAck decodeRegisterAck(byte[] bytes) {
        return read(bytes, in -> {
            boolean accepted = in.readBoolean();
            String errorCode = emptyToNull(in.readUTF());
            String podId = emptyToNull(in.readUTF());
            long heartbeat = in.readLong();
            Session session = readSession(in);
            return new RegisterAck(accepted, session, errorCode, podId, heartbeat);
        });
    }

    // --- HeartbeatMessage ---

    public static byte[] encodeHeartbeat(HeartbeatMessage m) {
        return write(out -> {
            out.writeUTF(m.sessionId());
            out.writeUTF(nz(m.ownerId()));
            out.writeLong(m.sentAtEpochMs());
        });
    }

    public static HeartbeatMessage decodeHeartbeat(byte[] bytes) {
        return read(bytes, in -> new HeartbeatMessage(in.readUTF(), in.readUTF(), in.readLong()));
    }

    // --- CloseMessage ---

    public static byte[] encodeClose(CloseMessage m) {
        return write(out -> {
            out.writeUTF(m.sessionId());
            out.writeUTF(nz(m.ownerId()));
            out.writeUTF(nz(m.reason()));
        });
    }

    public static CloseMessage decodeClose(byte[] bytes) {
        return read(bytes, in -> new CloseMessage(in.readUTF(), in.readUTF(), in.readUTF()));
    }

    // --- Session (nested in RegisterAck) ---

    private static void writeSession(DataOutputStream out, Session s) throws IOException {
        out.writeBoolean(s != null);
        if (s == null) {
            return;
        }
        out.writeUTF(s.sessionId());
        out.writeUTF(nz(s.subdomain()));
        out.writeUTF(nz(s.ownerId()));
        out.writeUTF(nz(s.podId()));
        out.writeUTF(s.status() == null ? SessionStatus.PENDING.name() : s.status().name());
        out.writeLong(s.createdAtEpochMs());
    }

    private static Session readSession(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        return new Session(
                in.readUTF(),
                emptyToNull(in.readUTF()),
                emptyToNull(in.readUTF()),
                emptyToNull(in.readUTF()),
                SessionStatus.valueOf(in.readUTF()),
                in.readLong());
    }

    // --- tiny functional helpers ---

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] write(Writer w) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            w.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArray streams don't do real I/O
        }
        return bos.toByteArray();
    }

    private static <T> T read(byte[] bytes, Reader<T> r) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return r.read(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed control payload", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
