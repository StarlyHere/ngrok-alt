package com.tunnel.transport.ssh;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.transport.TunnelConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.SubsystemFactory;

/**
 * The pod-side of the SSH transport (PRD-v2.md §8): an SSH server that, for each
 * connecting client's shell channel, runs the pod-side mux over the channel's
 * byte stream and hands the resulting {@link TunnelConnection} to a callback —
 * exactly what the WebSocket handler does, but over SSH.
 *
 * <p>SSH key auth would normally guard this (that's SSH's built-in advantage);
 * for the POC the server accepts the connection and the tunnel's own bearer-token
 * auth (carried as control frames) remains the security boundary.
 */
public final class SshTunnelServer implements AutoCloseable {

    private final SshServer server;

    /** Subsystem name the client opens to get a clean binary mux channel. */
    public static final String SUBSYSTEM = "tunnel";

    public SshTunnelServer(int port, Consumer<TunnelConnection> onConnect) throws IOException {
        this.server = SshServer.setUpDefaultServer();
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider()); // ephemeral in-memory host key
        server.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        server.setSubsystemFactories(List.of(new SubsystemFactory() {
            @Override
            public String getName() {
                return SUBSYSTEM;
            }

            @Override
            public Command createSubsystem(ChannelSession channel) {
                return new TunnelCommand(onConnect);
            }
        }));
        server.start();
    }

    /** The actual bound port (useful when constructed with port 0). */
    public int port() {
        return server.getPort();
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    /** A "shell" whose stdin/stdout become the mux byte pipe (pod = stream initiator). */
    private static final class TunnelCommand implements Command {

        private final Consumer<TunnelConnection> onConnect;
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;
        private FramedByteTransport transport;

        TunnelCommand(Consumer<TunnelConnection> onConnect) {
            this.onConnect = onConnect;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            // unused
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            // Pod is the stream initiator (opens a stream per inbound request).
            this.transport = new FramedByteTransport(in, out, true, (Consumer<Frame>) frame -> { });
            onConnect.accept(transport.connection());
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (transport != null) {
                transport.close();
            }
            if (exit != null) {
                exit.onExit(0);
            }
        }
    }
}
