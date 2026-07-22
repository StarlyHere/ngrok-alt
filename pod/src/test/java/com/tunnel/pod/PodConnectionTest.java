package com.tunnel.pod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tunnel.protocol.ErrorCodes;
import com.tunnel.protocol.codec.ControlCodec;
import com.tunnel.protocol.dto.RegisterAck;
import com.tunnel.protocol.dto.RegisterMessage;
import com.tunnel.protocol.frame.Frame;
import com.tunnel.protocol.frame.FrameType;
import com.tunnel.protocol.mux.FrameChannel;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PodConnectionTest {

    @Test
    void rejectsRegistrationWhenCoordinatorSessionDoesNotExist() throws Exception {
        FrameChannel sender = mock(FrameChannel.class);
        Runnable closer = mock(Runnable.class);
        ConnectionRegistry registry = new ConnectionRegistry();
        PodProperties properties = new PodProperties(
                "pod-1", "10.0.0.7", 8080, false,
                3_000, 6_000, 15_000, 256, 2222, 8182);
        PodRedisService redis = mock(PodRedisService.class);
        when(redis.sessionOwner("session-1")).thenReturn(Optional.empty());
        PodConnection connection = new PodConnection(sender, closer, registry, properties, redis, "owner-1");

        connection.onFrame(Frame.control(FrameType.REGISTER,
                ControlCodec.encodeRegister(new RegisterMessage("session-1", "owner-1", 8080, "ssh", "test"))));

        ArgumentCaptor<Frame> response = ArgumentCaptor.forClass(Frame.class);
        verify(sender).send(response.capture());
        RegisterAck ack = ControlCodec.decodeRegisterAck(response.getValue().payload());
        assertFalse(ack.accepted());
        assertEquals(ErrorCodes.OWNER_MISMATCH, ack.errorCode());
        assertFalse(registry.find("session-1").isPresent());
        verify(redis, never()).activateSession("session-1");
        verify(closer).run();
    }

    @Test
    void acceptsRegistrationOnlyForCoordinatorSessionOwner() throws Exception {
        FrameChannel sender = mock(FrameChannel.class);
        Runnable closer = mock(Runnable.class);
        ConnectionRegistry registry = new ConnectionRegistry();
        PodProperties properties = new PodProperties(
                "pod-1", "10.0.0.7", 8080, false,
                3_000, 6_000, 15_000, 256, 2222, 8182);
        PodRedisService redis = mock(PodRedisService.class);
        when(redis.sessionOwner("session-1")).thenReturn(Optional.of("owner-1"));
        PodConnection connection = new PodConnection(sender, closer, registry, properties, redis, "owner-1");

        connection.onFrame(Frame.control(FrameType.REGISTER,
                ControlCodec.encodeRegister(new RegisterMessage("session-1", "owner-1", 8080, "ssh", "test"))));

        ArgumentCaptor<Frame> response = ArgumentCaptor.forClass(Frame.class);
        verify(sender).send(response.capture());
        RegisterAck ack = ControlCodec.decodeRegisterAck(response.getValue().payload());
        assertEquals(FrameType.REGISTER_ACK, response.getValue().type());
        assertEquals("session-1", ack.session().sessionId());
        assertEquals(connection, registry.find("session-1").orElseThrow());
        verify(redis).activateSession("session-1");
        verify(redis).incrementConns();
        verify(closer, never()).run();
    }
}
