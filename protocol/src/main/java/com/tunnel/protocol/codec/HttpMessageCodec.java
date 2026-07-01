package com.tunnel.protocol.codec;

import com.tunnel.protocol.dto.HttpRequestMessage;
import com.tunnel.protocol.dto.HttpResponseMessage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes {@link HttpRequestMessage} and {@link HttpResponseMessage} over a
 * mux stream using a self-delimiting binary format ({@link DataInputStream}/
 * {@link DataOutputStream}). Each field is length-described, so the reader knows
 * exactly where the message ends without relying on stream EOF — which is what
 * lets a single bidirectional stream carry a request <em>then</em> a response.
 *
 * <p>Pure JDK I/O, no JSON dependency — keeps {@code protocol} dependency-free
 * and the native client thin.
 */
public final class HttpMessageCodec {

    private HttpMessageCodec() {
    }

    // --- request ---

    public static void writeRequest(OutputStream raw, HttpRequestMessage req) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw));
        out.writeUTF(req.method());
        out.writeUTF(req.path());
        out.writeUTF(req.query() == null ? "" : req.query());
        writeHeaders(out, req.headers());
        writeBody(out, req.body());
        out.flush();
    }

    public static HttpRequestMessage readRequest(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        String method = in.readUTF();
        String path = in.readUTF();
        String query = in.readUTF();
        Map<String, List<String>> headers = readHeaders(in);
        byte[] body = readBody(in);
        return new HttpRequestMessage(method, path, query, headers, body);
    }

    // --- response ---

    public static void writeResponse(OutputStream raw, HttpResponseMessage resp) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw));
        out.writeInt(resp.status());
        writeHeaders(out, resp.headers());
        writeBody(out, resp.body());
        out.flush();
    }

    public static HttpResponseMessage readResponse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        int status = in.readInt();
        Map<String, List<String>> headers = readHeaders(in);
        byte[] body = readBody(in);
        return new HttpResponseMessage(status, headers, body);
    }

    // --- shared field codecs ---

    private static void writeHeaders(DataOutputStream out, Map<String, List<String>> headers) throws IOException {
        Map<String, List<String>> h = headers == null ? Map.of() : headers;
        out.writeInt(h.size());
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            out.writeUTF(e.getKey());
            List<String> values = e.getValue() == null ? List.of() : e.getValue();
            out.writeInt(values.size());
            for (String v : values) {
                out.writeUTF(v == null ? "" : v);
            }
        }
    }

    private static Map<String, List<String>> readHeaders(DataInputStream in) throws IOException {
        int count = in.readInt();
        Map<String, List<String>> headers = new LinkedHashMap<>(Math.max(4, count * 2));
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            int valueCount = in.readInt();
            List<String> values = new ArrayList<>(valueCount);
            for (int j = 0; j < valueCount; j++) {
                values.add(in.readUTF());
            }
            headers.put(name, values);
        }
        return headers;
    }

    private static void writeBody(DataOutputStream out, byte[] body) throws IOException {
        byte[] b = body == null ? new byte[0] : body;
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBody(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] body = new byte[len];
        in.readFully(body);
        return body;
    }
}
