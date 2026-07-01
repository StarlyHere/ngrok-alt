package com.tunnel.qa6gateway;

import com.tunnel.protocol.TunnelConstants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Extracts the tunnel-routing indicator from a request and yields the candidate
 * session id. The {@code X-Tunnel-Session} header takes precedence; otherwise the
 * {@code remoteDebugConf} cookie's value is used (the value IS the session id).
 *
 * <p>Kept behind a component so a richer cookie format (subdomain / JSON) can be
 * swapped in later without touching the filter. Returns empty when neither is
 * present or both are blank → the request is treated as normal QA6 traffic.
 */
@Component
public class IndicatorExtractor {

    private final String cookieName;

    public IndicatorExtractor(GatewayProperties props) {
        this.cookieName = props.cookieName();
    }

    /** @return the session id this request is tagged for, or empty if untagged. */
    public Optional<String> extract(HttpServletRequest request) {
        String header = request.getHeader(TunnelConstants.HEADER_SESSION);
        if (header != null && !header.isBlank()) {
            return Optional.of(header.trim());
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return Optional.of(c.getValue().trim());
                }
            }
        }
        return Optional.empty();
    }
}
