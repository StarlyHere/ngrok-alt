package com.tunnel.control;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.codec.AssignmentCodec;
import com.tunnel.protocol.dto.AssignmentRequest;
import com.tunnel.protocol.dto.AssignmentResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Coordinator's assignment surface (BUILD-CHECKLIST.md Part 3–4). Now
 * authenticated: the request must carry a valid {@code Authorization: Bearer}
 * token (§9.2.2); the session is bound to the token's owner (§9.2.3). An
 * unauthenticated request is rejected with {@code 401 unauthorized}.
 */
@RestController
public class AssignmentController {

    private final SessionCoordinator coordinator;
    private final AuthService auth;

    public AssignmentController(SessionCoordinator coordinator, AuthService auth) {
        this.coordinator = coordinator;
        this.auth = auth;
    }

    @PostMapping(value = "/sessions",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> assign(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) byte[] body) {
        // Auth is checked first — before any body validation — so an unauthenticated
        // request is always a clean 401, never a 400.
        Optional<String> owner = auth.ownerForAuthHeader(authorization);
        if (owner.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AssignmentCodec.encodeResponse(AssignmentResponse.rejected(ErrorCodes.UNAUTHORIZED)));
        }
        if (body == null || body.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AssignmentCodec.encodeResponse(AssignmentResponse.rejected(ErrorCodes.BAD_REQUEST)));
        }

        AssignmentRequest req = AssignmentCodec.decodeRequest(body);
        AssignmentResponse resp = coordinator.assign(req, owner.get());
        HttpStatus status = resp.accepted() ? HttpStatus.OK : statusFor(resp.errorCode());
        return ResponseEntity.status(status).body(AssignmentCodec.encodeResponse(resp));
    }

    private static HttpStatus statusFor(String errorCode) {
        return switch (errorCode) {
            case ErrorCodes.OWNER_MISMATCH -> HttpStatus.FORBIDDEN;
            case ErrorCodes.QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case ErrorCodes.NO_POD_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Explicitly closes a session. The client calls this on clean shutdown so the
     * ingress is deleted immediately rather than waiting for the reconciler (step 8).
     * Requires the same bearer token that opened the session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId) {
        if (auth.ownerForAuthHeader(authorization).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        coordinator.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** Liveness probe. */
    @GetMapping("/healthz")
    public String health() {
        return "ok";
    }
}
