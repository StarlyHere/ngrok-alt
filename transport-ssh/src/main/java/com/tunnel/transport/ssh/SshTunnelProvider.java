package com.tunnel.transport.ssh;

import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.transport.TunnelConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

/**
 * The client-side SSH realization of {@link com.tunnel.protocol.transport.TunnelProvider}
 * (PRD-v2.md §8). Opens one outbound SSH connection to a pod, opens a shell
 * channel, and runs the same mux over that channel's byte stream. Drop-in
 * alternative to the WebSocket provider — same {@link TunnelConnection} contract.
 */
public final class SshTunnelProvider implements com.tunnel.protocol.transport.TunnelProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Override
    public String name() {
        return "ssh";
    }

    @Override
    public TunnelConnection open(URI endpoint, Map<String, String> headers) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();

        ClientSession session = client.connect("tunnel", endpoint.getHost(), endpoint.getPort())
                .verify(TIMEOUT).getSession();

        // Use the bearer token as the SSH password so the pod can validate it against Redis.
        String authHeader = headers != null
                ? headers.getOrDefault("Authorization", "") : "";
        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length()) : authHeader;
        session.addPasswordIdentity(token.isBlank() ? "tunnel" : token);
        session.auth().verify(TIMEOUT);

        ChannelSubsystem channel = session.createSubsystemChannel(SshTunnelServer.SUBSYSTEM);
        // Sync streaming exposes the inverted in/out streams as a plain byte pipe.
        channel.setStreaming(ClientChannel.Streaming.Sync);
        channel.open().verify(TIMEOUT);

        // Mutable control-handler reference: the caller sets the real handler via
        // setControlHandler() after open() returns, matching the WS provider contract.
        java.util.concurrent.atomic.AtomicReference<Consumer<Frame>> controlRef =
                new java.util.concurrent.atomic.AtomicReference<>(f -> {});

        // getInvertedOut(): bytes from the server; getInvertedIn(): bytes to the server.
        // Client accepts streams (the pod opens them), so localInitiator = false.
        FramedByteTransport transport = new FramedByteTransport(
                channel.getInvertedOut(), channel.getInvertedIn(), false,
                frame -> controlRef.get().accept(frame));
        return new SshTunnelConnection(transport.connection(), transport, controlRef);
    }
}
