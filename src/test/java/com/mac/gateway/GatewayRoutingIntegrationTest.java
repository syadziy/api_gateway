package com.mac.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.enabled=false",
        "gateway.audit.enabled=false",
        "management.server.port=0",
        "gateway.http.max-request-size=1KB"
})
@EnabledIfEnvironmentVariable(named = "RUN_NETWORK_INTEGRATION_TESTS", matches = "true")
class GatewayRoutingIntegrationTest {

    private static final MockWebServer UPSTREAM = startServer();

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private RedisRateLimiter rateLimiter;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes.alert", () -> UPSTREAM.url("/").toString());
        registry.add("gateway.routes.scheduler", () -> UPSTREAM.url("/").toString());
        registry.add("gateway.routes.audit", () -> UPSTREAM.url("/").toString());
        registry.add("gateway.routes.usermanagement", () -> UPSTREAM.url("/").toString());
    }

    @BeforeEach
    void allowRequests() {
        when(rateLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    @AfterAll
    static void stopServer() throws IOException {
        UPSTREAM.shutdown();
    }

    @Test
    void routesRequestAndPropagatesSafeCorrelationId() throws Exception {
        UPSTREAM.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"ok\"}"));

        client.get().uri("/api/v1/audit-logs?limit=10")
                .header("X-Correlation-Id", "trace-100")
                .header("X-Forwarded-For", "spoofed")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "trace-100")
                .expectBody().json("{\"result\":\"ok\"}");

        RecordedRequest request = UPSTREAM.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/v1/audit-logs?limit=10");
        assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("trace-100");
        assertThat(request.getHeader("X-Forwarded-For")).isNotEqualTo("spoofed");
    }

    @Test
    void rejectsRateLimitAndUnknownRoute() {
        when(rateLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));

        client.get().uri("/api/v1/audit-logs")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        client.get().uri("/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start mock upstream", exception);
        }
    }
}
