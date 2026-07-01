package com.tunnel.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues bearer tokens for {@code tunnel login} (PRD-v2.md §9.2.2). A Tier-1
 * stand-in for SSO: trust the supplied user name and mint a token bound to it.
 * The token is returned as a plain string so the client needs no JSON parser.
 */
@RestController
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final AuthService auth;

    public LoginController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping(value = "/login", produces = MediaType.TEXT_PLAIN_VALUE)
    public String login(@RequestParam(name = "user", defaultValue = "dev") String user) {
        String token = auth.issueToken(user);
        // Never log the token itself.
        log.info("issued token for owner '{}'", user);
        return token;
    }
}
