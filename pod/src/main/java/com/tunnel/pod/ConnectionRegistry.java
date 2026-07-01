package com.tunnel.pod;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of the client connections this pod currently holds
 * (PRD-v2.md §6; BUILD-CHECKLIST.md Part 2). Keyed by session id.
 *
 * <p>Part 2 has no Redis: routing falls back to the sole connection when no
 * explicit session is named. Part 3 layers a Redis {@code session→pod} lookup
 * on top, but this local registry stays the source of the actual socket.
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final ConcurrentHashMap<String, PodConnection> bySession = new ConcurrentHashMap<>();

    /** Register (or replace) the connection owning a session. */
    public void register(String sessionId, PodConnection connection) {
        bySession.put(sessionId, connection);
        log.info("registered session {} (active sessions: {})",
                redact(sessionId), bySession.size());
    }

    /** Remove a session's connection, if present. */
    public void unregister(String sessionId) {
        if (sessionId != null && bySession.remove(sessionId) != null) {
            log.info("unregistered session {} (active sessions: {})",
                    redact(sessionId), bySession.size());
        }
    }

    /** Look up the connection for a specific session. */
    public Optional<PodConnection> find(String sessionId) {
        return Optional.ofNullable(sessionId).map(bySession::get);
    }

    /**
     * Resolve the connection to forward a request to. Prefers the named session;
     * otherwise, in the single-tunnel Part 2 setup, falls back to the sole
     * registered connection. Returns empty if ambiguous or none.
     */
    public Optional<PodConnection> resolve(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return find(sessionId);
        }
        return bySession.size() == 1 ? bySession.values().stream().findFirst() : Optional.empty();
    }

    public Collection<PodConnection> all() {
        return bySession.values();
    }

    public int activeSessions() {
        return bySession.size();
    }

    private static String redact(String sessionId) {
        return sessionId == null || sessionId.length() <= 6 ? "******" : sessionId.substring(0, 6) + "…";
    }
}
