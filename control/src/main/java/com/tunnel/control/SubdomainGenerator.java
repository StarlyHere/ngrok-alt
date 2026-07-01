package com.tunnel.control;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Derives a stable, friendly subdomain (e.g. {@code bright-otter-12}) from a
 * session id (PRD-v2.md §7). Deterministic on purpose: the same session always
 * maps to the same name, so a reconnect after TTL expiry resumes on the same URL
 * without the Coordinator having to persist the name.
 */
@Component
public class SubdomainGenerator {

    private static final List<String> ADJECTIVES = List.of(
            "bright", "calm", "swift", "brave", "clever", "gentle", "lucky", "bold",
            "quiet", "sunny", "eager", "fancy", "merry", "noble", "proud", "witty");

    private static final List<String> ANIMALS = List.of(
            "otter", "falcon", "panda", "lynx", "heron", "koala", "tiger", "raven",
            "moose", "gecko", "bison", "finch", "shark", "ibex", "wolf", "crane");

    /** Map a session id to a deterministic {@code adj-animal-NN} label. */
    public String forSession(String sessionId) {
        int h = stableHash(sessionId);
        int adj = (h & 0xFF) % ADJECTIVES.size();
        int animal = ((h >> 8) & 0xFF) % ANIMALS.size();
        int num = ((h >> 16) & 0xFF) % 100;
        return ADJECTIVES.get(adj) + "-" + ANIMALS.get(animal) + "-" + num;
    }

    /** FNV-1a — stable across JVMs (unlike String.hashCode guarantees in spirit). */
    private static int stableHash(String s) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }
}
