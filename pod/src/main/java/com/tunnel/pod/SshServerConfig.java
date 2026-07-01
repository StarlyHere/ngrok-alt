package com.tunnel.pod;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import jakarta.annotation.PreDestroy;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starts an Apache SSHD server on {@code pod.ssh-port} (default 2222).
 * Each SSH connection authenticated with a valid bearer token becomes a
 * {@link PodConnection} — same tunnel protocol as before, now over SSH bytes
 * instead of WebSocket messages.
 */
@Configuration
public class SshServerConfig {

    private static final Logger log = LoggerFactory.getLogger(SshServerConfig.class);

    /** Per-session attribute that stores the authenticated owner id. */
    private static final AttributeRepository.AttributeKey<String> OWNER_KEY =
            new AttributeRepository.AttributeKey<>();

    @Bean
    public SshServer sshTunnelServer(AuthService auth, ConnectionRegistry registry,
                                     PodProperties props, PodRedisService redis) throws IOException {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(props.sshPort());
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());

        // SSH password = bearer token; the same Redis auth:token:{token} → ownerId
        // lookup used by the WebSocket handshake interceptor.
        sshd.setPasswordAuthenticator((username, password, session) -> {
            Optional<String> owner = auth.ownerForAuthHeader("Bearer " + password);
            owner.ifPresent(o -> session.setAttribute(OWNER_KEY, o));
            return owner.isPresent();
        });

        sshd.setSubsystemFactories(List.of(new SubsystemFactory() {
            @Override
            public String getName() { return "tunnel"; }

            @Override
            public Command createSubsystem(ChannelSession channel) {
                return new TunnelSubsystem(channel, registry, props, redis);
            }
        }));

        sshd.start();
        log.info("SSH tunnel server listening on port {}", props.sshPort());
        return sshd;
    }

    /**
     * SSH subsystem command that bridges the SSH byte-stream to {@link PodConnection}.
     * Reads length-delimited frames from the SSH input stream and feeds them to the
     * pod's mux; sends outbound frames via {@link FrameCodec#write}.
     */
    private static class TunnelSubsystem implements Command {

        private final ChannelSession channel;
        private final ConnectionRegistry registry;
        private final PodProperties props;
        private final PodRedisService redis;

        private InputStream in;
        private OutputStream out;
        private ExitCallback exitCallback;
        private PodConnection podConn;
        private Thread reader;

        TunnelSubsystem(ChannelSession channel, ConnectionRegistry registry,
                        PodProperties props, PodRedisService redis) {
            this.channel = channel;
            this.registry = registry;
            this.props = props;
            this.redis = redis;
        }

        @Override
        public void setInputStream(InputStream in) { this.in = in; }

        @Override
        public void setOutputStream(OutputStream out) { this.out = out; }

        @Override
        public void setErrorStream(OutputStream err) {}

        @Override
        public void setExitCallback(ExitCallback callback) { this.exitCallback = callback; }

        @Override
        public void start(ChannelSession channelSession, Environment env) {
            String ownerId = channelSession.getSession().getAttribute(OWNER_KEY);
            OutputStream sink = out;

            podConn = new PodConnection(
                frame -> {
                    synchronized (sink) {
                        FrameCodec.write(sink, frame);
                        sink.flush();
                    }
                },
                () -> {
                    try { sink.close(); } catch (IOException ignored) {}
                },
                registry, props, redis, ownerId);

            reader = new Thread(() -> {
                try {
                    Frame frame;
                    while ((frame = FrameCodec.read(in)) != null) {
                        podConn.onFrame(frame);
                    }
                } catch (IOException ignored) {
                    // pipe closed normally
                } finally {
                    podConn.close();
                    if (exitCallback != null) exitCallback.onExit(0);
                }
            }, "ssh-tunnel-reader");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void destroy(ChannelSession channelSession) {
            if (reader != null) reader.interrupt();
            if (podConn != null) podConn.close();
        }
    }
}
