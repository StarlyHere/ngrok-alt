package com.tunnel.client;

import com.tunnel.protocol.TunnelConstants;
import com.tunnel.protocol.codec.AssignmentCodec;
import com.tunnel.protocol.dto.AssignmentRequest;
import com.tunnel.protocol.dto.AssignmentResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the Coordinator's assignment API (PRD-v2.md §4 path A) over plain
 * HTTP using the protocol's {@code octet-stream} codec — so the client needs no
 * JSON library and stays thin (PRD-v2.md §7).
 */
public class CoordinatorClient {

    private static final String OCTET_STREAM = "application/octet-stream";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI coordinatorBase;
    private final URI sessionsEndpoint;
    private final String token;

    public CoordinatorClient(URI coordinatorBase, String token) {
        this.coordinatorBase = coordinatorBase;
        this.sessionsEndpoint = coordinatorBase.resolve("/sessions");
        this.token = token;
    }

    /** Request (or, with an existing session id, reaffirm) a pod assignment. */
    public AssignmentResponse obtain(AssignmentRequest request) throws IOException, InterruptedException {
        byte[] body = AssignmentCodec.encodeRequest(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(sessionsEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", OCTET_STREAM)
                .header("Accept", OCTET_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null && !token.isBlank()) {
            builder.header(TunnelConstants.HEADER_AUTHORIZATION, TunnelConstants.BEARER_PREFIX + token);
        }
        HttpRequest httpRequest = builder.build();

        HttpResponse<byte[]> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("coordinator returned HTTP " + response.statusCode());
        }
        return AssignmentCodec.decodeResponse(response.body());
    }

    /**
     * Notifies the coordinator that the session is closing so it can delete the
     * ingress immediately. Best-effort — failures are logged by the caller.
     */
    public void deleteSession(String sessionId) throws IOException, InterruptedException {
        URI endpoint = coordinatorBase.resolve("/sessions/" + sessionId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .DELETE();
        if (token != null && !token.isBlank()) {
            builder.header(TunnelConstants.HEADER_AUTHORIZATION, TunnelConstants.BEARER_PREFIX + token);
        }
        http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }
}
