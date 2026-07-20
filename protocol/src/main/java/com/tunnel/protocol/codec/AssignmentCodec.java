package com.tunnel.protocol.codec;

import com.tunnel.protocol.dto.AssignmentRequest;
import com.tunnel.protocol.dto.AssignmentResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Serializes the Coordinator assignment API ({@link AssignmentRequest} /
 * {@link AssignmentResponse}) as {@code application/octet-stream} bodies.
 *
 * <p>Using the same dependency-free DataStream form as the rest of the protocol
 * keeps the client free of any JSON library — it stays thin and native-friendly
 * (PRD-v2.md §7) while still talking to the control plane over plain HTTP.
 */
public final class AssignmentCodec {

    private AssignmentCodec() {
    }

    public static byte[] encodeRequest(AssignmentRequest r) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeUTF(nz(r.ownerId()));
            out.writeInt(r.targetPort());
            out.writeUTF(nz(r.existingSessionId()));
            out.writeUTF(nz(r.pathPatterns()));
            out.writeBoolean(r.createIngress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static AssignmentRequest decodeRequest(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String ownerId = in.readUTF();
            int targetPort = in.readInt();
            String existing = emptyToNull(in.readUTF());
            String pathPatterns = emptyToNull(in.readUTF());
            boolean createIngress = in.readBoolean();
            return new AssignmentRequest(ownerId, targetPort, existing, pathPatterns, createIngress);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed assignment request", e);
        }
    }

    public static byte[] encodeResponse(AssignmentResponse r) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeUTF(nz(r.sessionId()));
            out.writeUTF(nz(r.subdomain()));
            out.writeUTF(nz(r.podId()));
            out.writeUTF(nz(r.podWsUrl()));
            out.writeLong(r.heartbeatIntervalMs());
            out.writeUTF(nz(r.errorCode()));
            out.writeUTF(nz(r.pathPatterns()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static AssignmentResponse decodeResponse(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String sessionId = emptyToNull(in.readUTF());
            String subdomain = emptyToNull(in.readUTF());
            String podId = emptyToNull(in.readUTF());
            String podWsUrl = emptyToNull(in.readUTF());
            long heartbeat = in.readLong();
            String errorCode = emptyToNull(in.readUTF());
            String pathPatterns = emptyToNull(in.readUTF());
            return new AssignmentResponse(sessionId, subdomain, podId, podWsUrl, heartbeat, errorCode, pathPatterns);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed assignment response", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
