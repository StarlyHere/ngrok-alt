package com.tunnel.pod;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    @Test
    void closingReplacedConnectionDoesNotRemoveCurrentConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();
        PodConnection oldConnection = mock(PodConnection.class);
        PodConnection newConnection = mock(PodConnection.class);

        registry.register("session-1", oldConnection);
        registry.register("session-1", newConnection);
        registry.unregister("session-1", oldConnection);

        assertSame(newConnection, registry.find("session-1").orElseThrow());
    }
}
