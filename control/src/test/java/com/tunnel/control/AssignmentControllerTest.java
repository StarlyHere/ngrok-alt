package com.tunnel.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AssignmentControllerTest {

    private final SessionCoordinator coordinator = mock(SessionCoordinator.class);
    private final AuthService auth = mock(AuthService.class);
    private final AssignmentController controller = new AssignmentController(coordinator, auth);

    @Test
    void ownerCanDeleteSession() {
        when(auth.ownerForAuthHeader("Bearer token")).thenReturn(Optional.of("alice"));
        when(coordinator.deleteSession("session-1", "alice")).thenReturn(true);

        assertEquals(HttpStatus.NO_CONTENT,
                controller.delete("Bearer token", "session-1").getStatusCode());
        verify(coordinator).deleteSession("session-1", "alice");
    }

    @Test
    void nonOwnerCannotDeleteSession() {
        when(auth.ownerForAuthHeader("Bearer token")).thenReturn(Optional.of("mallory"));
        when(coordinator.deleteSession("session-1", "mallory")).thenReturn(false);

        assertEquals(HttpStatus.FORBIDDEN,
                controller.delete("Bearer token", "session-1").getStatusCode());
    }

    @Test
    void unauthenticatedDeleteIsRejected() {
        when(auth.ownerForAuthHeader(null)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.delete(null, "session-1").getStatusCode());
    }
}
