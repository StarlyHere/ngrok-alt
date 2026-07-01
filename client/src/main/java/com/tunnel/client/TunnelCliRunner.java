package com.tunnel.client;

import com.tunnel.protocol.TunnelConstants;
import com.tunnel.client.transport.WebSocketTunnelProvider;
import com.tunnel.transport.ssh.SshTunnelProvider;
import com.tunnel.protocol.transport.TunnelProvider;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * CLI surface for the client binary (PRD-v2.md §7; BUILD-CHECKLIST.md Part 2):
 *
 * <pre>
 *   tunnel http &lt;port&gt; [--pod=ws://host:port/tunnel] [--inspector=4040] [--session=&lt;id&gt;]
 * </pre>
 *
 * Parses arguments, builds a {@link TunnelConfig}, and runs the blocking
 * {@link TunnelClient}. A stable random session id is generated per run so a
 * reconnect resumes on the same URL.
 */
@Component
public class TunnelCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TunnelCliRunner.class);
    private static final String DEFAULT_COORDINATOR = "http://localhost:8090";

    private final TokenStore tokenStore = new TokenStore();

    @Override
    public void run(ApplicationArguments args) {
        List<String> positional = args.getNonOptionArgs();
        String command = positional.isEmpty() ? "" : positional.get(0).toLowerCase();

        if (command.equals("login")) {
            URI coordinator = URI.create(optionOr(args, "coordinator",
                    System.getenv().getOrDefault("COORDINATOR_URL", DEFAULT_COORDINATOR)));
            String user = optionOr(args, "user", System.getProperty("user.name", "dev"));
            new LoginCommand().run(coordinator, user);
            return;
        }

        if (positional.size() < 2 || !command.equals("http")) {
            printUsage();
            return;
        }

        int port = parsePort(positional.get(1));
        if (port < 1) {
            return;
        }

        int inspectorPort = Integer.parseInt(
                optionOr(args, "inspector", String.valueOf(TunnelConstants.INSPECTOR_PORT)));
        String sessionId = optionOr(args, "session", null); // Coordinator issues it otherwise
        // Forward target host — loopback by default (the dev's local app); a named
        // service in the in-cluster demo where the app is a separate pod.
        String targetHost = optionOr(args, "target-host", "127.0.0.1");
        String owner = tokenStore.owner() != null ? tokenStore.owner() : System.getProperty("user.name", "dev");

        // Default: go through the Coordinator (Part 3). --pod forces direct mode (Part 2 style).
        String directPod = optionOr(args, "pod", null);
        TunnelConfig config;
        if (directPod != null) {
            config = new TunnelConfig(
                    null, URI.create(directPod), targetHost, port,
                    sessionId != null ? sessionId : newSessionId(), owner, tokenStore.token(),
                    "0.1.0-SNAPSHOT", inspectorPort, TunnelConstants.DEFAULT_HEARTBEAT_INTERVAL_MS);
        } else {
            String token = optionOr(args, "token", tokenStore.token());
            if (token == null || token.isBlank()) {
                log.error("not logged in — run `tunnel login` first (no token at {})", tokenStore.path());
                return;
            }
            URI coordinator = URI.create(optionOr(args, "coordinator",
                    System.getenv().getOrDefault("COORDINATOR_URL", DEFAULT_COORDINATOR)));
            config = new TunnelConfig(coordinator, null, targetHost, port, sessionId, owner, token,
                    "0.1.0-SNAPSHOT", inspectorPort, TunnelConstants.DEFAULT_HEARTBEAT_INTERVAL_MS);
        }

        String transport = optionOr(args, "transport", "ws");
        TunnelProvider provider = "ssh".equals(transport)
                ? new SshTunnelProvider()
                : new WebSocketTunnelProvider();

        TunnelClient client = new TunnelClient(config, provider);
        Runtime.getRuntime().addShutdownHook(new Thread(client::shutdown, "tunnel-shutdown"));
        try {
            client.run();
        } catch (Exception e) {
            log.error("tunnel exited: {}", e.toString());
        }
    }

    private int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            log.error("invalid port: {}", raw);
            printUsage();
            return -1;
        }
    }

    private static String optionOr(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static String newSessionId() {
        byte[] bytes = new byte[TunnelConstants.SESSION_ID_BITS / 8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void printUsage() {
        log.info("usage:");
        log.info("  tunnel login [--user=<name>] [--coordinator=http://host:port]");
        log.info("  tunnel http <port> [--coordinator=http://host:port] "
                + "[--pod=ws://host:port/tunnel] [--transport=ws|ssh] [--inspector=4040] [--session=<id>]");
    }
}
