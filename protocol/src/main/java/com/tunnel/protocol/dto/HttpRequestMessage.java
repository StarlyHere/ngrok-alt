package com.tunnel.protocol.dto;

import java.util.List;
import java.util.Map;

/**
 * A serialized HTTP request travelling down a tunnel stream from pod to client
 * (the inbound QA6 request). Transport-neutral: see
 * {@code com.tunnel.protocol.codec.HttpMessageCodec} for the wire form.
 *
 * @param method  HTTP method, e.g. {@code GET}
 * @param path    request path, e.g. {@code /hello}
 * @param query   raw query string without {@code ?} ({@code ""} if none)
 * @param headers request headers (multi-valued)
 * @param body    request body bytes (empty array if none)
 */
public record HttpRequestMessage(
        String method,
        String path,
        String query,
        Map<String, List<String>> headers,
        byte[] body) {
}
