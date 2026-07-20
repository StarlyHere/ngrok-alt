package com.tunnel.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import com.tunnel.protocol.TunnelConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RoutingFilterTest {

    @Test
    void reconstructsOriginalSprinklrIngressUrlFromForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "space-qa6-app-workflow.sprinklr.com");

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "", "qa6", ".sprinklr.com"))
                .isEqualTo("https://space-qa6-app-workflow.sprinklr.com");
    }

    @Test
    void configuredFallbackUrlOverridesOriginalIngressAndDropsTrailingSlash() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "http://webui.spr-apps:8080/", "", ""))
                .isEqualTo("http://webui.spr-apps:8080");
    }

    @Test
    void rejectsUntrustedDynamicFallbackHost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "169.254.169.254");

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "", "qa6", ".sprinklr.com")).isNull();
    }

    @Test
    void rejectsMalformedHostThatOnlyEndsWithSprinklrSuffix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "attacker.invalid?next=.sprinklr.com");

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "", "qa6", ".sprinklr.com")).isNull();
    }

    @Test
    void rejectsAnotherSprinklrEnvironment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "space-prod-app-workflow.sprinklr.com");

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "", "qa6", ".sprinklr.com")).isNull();
    }

    @Test
    void forcesHttpsForDynamicFallbackEvenWhenForwardedProtocolIsHttp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("Host", "qa6-app-workflow.sprinklr.com");

        assertThat(RoutingFilter.fallbackBaseUrl(
                request, "", "qa6", ".sprinklr.com"))
                .isEqualTo("https://qa6-app-workflow.sprinklr.com");
    }

    @Test
    void removesOnlyLocalConnectRoutingCookiesFromFallbackRequest() {
        String cookies = "JSESSIONID=abc; sprLocalConnect=always; "
                + "remoteDebugConf=session-1; SPR_STICKINESS=node-a; n=ENTERPRISE";

        assertThat(RoutingFilter.withoutRoutingCookies(
                cookies, "sprLocalConnect", "remoteDebugConf"))
                .isEqualTo("JSESSIONID=abc; SPR_STICKINESS=node-a; n=ENTERPRISE");
    }

    @Test
    void replaysTunnelMissWithoutRoutingCookiesOrSessionHeader() throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        AtomicReference<String> receivedCookie = new AtomicReference<>();
        AtomicReference<String> receivedSessionHeader = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer fallback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fallback.createContext("/ui/graphql/test", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            receivedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            receivedSessionHeader.set(exchange.getRequestHeaders().getFirst(TunnelConstants.HEADER_SESSION));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "normal-webui".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        fallback.start();

        try {
            PodResolver resolver = mock(PodResolver.class);
            RateLimiter rateLimiter = mock(RateLimiter.class);
            when(rateLimiter.allow(eq("missing-session"), anyLong())).thenReturn(true);
            when(resolver.targetForSession("missing-session")).thenReturn(Optional.empty());
            RoutingFilter filter = new RoutingFilter(
                    resolver, rateLimiter, new SimpleMeterRegistry());
            ReflectionTestUtils.setField(filter, "cookieName", "remoteDebugConf");
            ReflectionTestUtils.setField(filter, "selectorCookieName", "sprLocalConnect");
            ReflectionTestUtils.setField(filter, "webuiFallbackUrl",
                    "http://127.0.0.1:" + fallback.getAddress().getPort());
            ReflectionTestUtils.setField(filter, "fallbackEnvironment", "");
            ReflectionTestUtils.setField(filter, "fallbackDomainSuffix", "");

            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/ui/graphql/test");
            request.setQueryString("op=search");
            request.setContent("request-body".getBytes(StandardCharsets.UTF_8));
            request.addHeader(TunnelConstants.HEADER_SESSION, "missing-session");
            request.addHeader("Cookie", "sprLocalConnect=always; remoteDebugConf=missing-session; "
                    + "JSESSIONID=abc; SPR_STICKINESS=node-a");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, (req, res) -> { });

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("normal-webui");
            assertThat(receivedMethod).hasValue("POST");
            assertThat(receivedQuery).hasValue("op=search");
            assertThat(receivedBody).hasValue("request-body");
            assertThat(receivedCookie).hasValue("JSESSIONID=abc; SPR_STICKINESS=node-a");
            assertThat(receivedSessionHeader.get()).isNull();
        } finally {
            fallback.stop(0);
        }
    }

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
