package com.tunnel.qa6gateway;

import org.springframework.util.AntPathMatcher;

/**
 * Decides whether a request path should be tunneled based on the session's
 * stored path-pattern allowlist.
 *
 * <p>Pattern format: comma-separated Ant patterns, e.g. {@code "/process/**,/ui/graphql/**"}.
 * {@code null}, blank, or the literal {@code "ALL"} means intercept everything.
 */
class PathMatcher {

    private static final AntPathMatcher ANT = new AntPathMatcher();

    private PathMatcher() {}

    /**
     * Returns {@code true} if {@code requestPath} should be forwarded through the tunnel.
     *
     * @param pathPatterns comma-separated Ant patterns from the session hash, or {@code null}/ALL
     * @param requestPath  the incoming request URI (no query string)
     */
    static boolean matches(String pathPatterns, String requestPath) {
        if (pathPatterns == null || pathPatterns.isBlank() || "ALL".equalsIgnoreCase(pathPatterns.trim())) {
            return true;
        }
        for (String raw : pathPatterns.split(",")) {
            String pattern = raw.strip();
            if (!pattern.isEmpty() && ANT.match(pattern, requestPath)) {
                return true;
            }
        }
        return false;
    }
}
