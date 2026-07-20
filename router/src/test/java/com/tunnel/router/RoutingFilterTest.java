package com.tunnel.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RoutingFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/health/liveness"
    })
    void actuatorHealthRequestsBypassTunnelRouting(String path) throws Exception {
        PodResolver resolver = mock(PodResolver.class);
        RoutingFilter filter = new RoutingFilter(
                resolver, mock(RateLimiter.class), new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        verifyNoInteractions(resolver);
    }
}
