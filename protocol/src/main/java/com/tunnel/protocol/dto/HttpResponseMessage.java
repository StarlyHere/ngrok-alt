package com.tunnel.protocol.dto;

import java.util.List;
import java.util.Map;

/**
 * A serialized HTTP response travelling back up a tunnel stream from client to
 * pod (the dev service's reply). See
 * {@code com.tunnel.protocol.codec.HttpMessageCodec} for the wire form.
 *
 * @param status  HTTP status code, e.g. {@code 200}
 * @param headers response headers (multi-valued)
 * @param body    response body bytes (empty array if none)
 */
public record HttpResponseMessage(
        int status,
        Map<String, List<String>> headers,
        byte[] body) {
}
