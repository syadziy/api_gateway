package com.mac.gateway.security;

import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=production",
        "gateway.security.enabled=true",
        "gateway.security.issuer-uri=https://issuer.example",
        "gateway.security.audience=api-gateway",
        "gateway.audit.enabled=false",
        "management.server.port=0"
})
class GatewaySecurityIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    private RedisRateLimiter rateLimiter;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void rejectsMissingAndInvalidTokenWithJsonResponse() {
        client.get().uri("/api/v1/audit-logs")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_UNAUTHORIZED")
                .jsonPath("$.traceId").isNotEmpty();

        when(jwtDecoder.decode("bad-token")).thenReturn(Mono.error(new BadJwtException("invalid")));
        client.get().uri("/api/v1/audit-logs")
                .headers(headers -> headers.setBearerAuth("bad-token"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deniesTokenWithoutRequiredRouteScope() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("wrong-scope")
                .header("alg", "RS256")
                .subject("user-1")
                .issuer("https://issuer.example")
                .audience(List.of("api-gateway"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("scope", "alert.write")
                .build();
        when(jwtDecoder.decode("wrong-scope")).thenReturn(Mono.just(jwt));

        client.get().uri("/api/v1/audit-logs")
                .headers(headers -> headers.setBearerAuth("wrong-scope"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_FORBIDDEN");
    }

    @Test
    void enforcesDedicatedRecipientDashboardScopes() {
        Instant now = Instant.now();
        Jwt readJwt = jwt("recipient-read", now, "alert.read-recipients");
        Jwt manageJwt = jwt("recipient-manage", now, "alert.manage-recipients");
        when(jwtDecoder.decode("recipient-read")).thenReturn(Mono.just(readJwt));
        when(jwtDecoder.decode("recipient-manage")).thenReturn(Mono.just(manageJwt));

        client.get().uri("/api/v1/alert/recipients")
                .headers(headers -> headers.setBearerAuth("recipient-read"))
                .exchange()
                .expectStatus().value(status -> assertAuthorized(status));
        client.post().uri("/api/v1/alert/recipients")
                .headers(headers -> headers.setBearerAuth("recipient-read"))
                .exchange()
                .expectStatus().isForbidden();
        client.post().uri("/api/v1/alert/recipients")
                .headers(headers -> headers.setBearerAuth("recipient-manage"))
                .exchange()
                .expectStatus().value(status -> assertAuthorized(status));
    }

    @Test
    void exposesPublicIdentityEndpointsAndProtectsTenantOperations() {
        client.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
        client.post().uri("/api/v1/tenants")
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);

        Instant now = Instant.now();
        when(jwtDecoder.decode("user-view"))
                .thenReturn(Mono.just(jwt("user-view", now, "user.view")));
        when(jwtDecoder.decode("user-create"))
                .thenReturn(Mono.just(jwt("user-create", now, "user.create")));

        client.get().uri("/api/v1/tenants/11111111-1111-1111-1111-111111111111/users")
                .headers(headers -> headers.setBearerAuth("user-view"))
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
        client.get().uri("/api/v1/tenants/11111111-1111-1111-1111-111111111111/users")
                .headers(headers -> headers.setBearerAuth("user-create"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void separatesSchedulerReadAndManageScopesByHttpMethod() {
        Instant now = Instant.now();
        when(jwtDecoder.decode("scheduler-read"))
                .thenReturn(Mono.just(jwt("scheduler-read", now, "scheduler.read")));
        when(jwtDecoder.decode("scheduler-manage"))
                .thenReturn(Mono.just(jwt("scheduler-manage", now, "scheduler.manage")));

        client.get().uri("/api/v1/tasks")
                .headers(headers -> headers.setBearerAuth("scheduler-read"))
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
        client.post().uri("/api/v1/tasks")
                .headers(headers -> headers.setBearerAuth("scheduler-read"))
                .exchange()
                .expectStatus().isForbidden();
        client.post().uri("/api/v1/tasks")
                .headers(headers -> headers.setBearerAuth("scheduler-manage"))
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
    }

    @Test
    void internalPathDoesNotRequireJwt() {
        client.get().uri("/internal/readiness")
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
    }

    @Test
    void websocketHandshakeIsPublicBecauseStompConnectValidatesJwtDownstream() {
        client.get().uri("/ws/alerts")
                .exchange()
                .expectStatus().value(GatewaySecurityIntegrationTest::assertAuthorized);
    }

    private static Jwt jwt(String token, Instant now, String scope) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject("user-1")
                .issuer("https://issuer.example")
                .audience(List.of("api-gateway"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("scope", scope)
                .build();
    }

    private static void assertAuthorized(int status) {
        org.junit.jupiter.api.Assertions.assertNotEquals(401, status);
        org.junit.jupiter.api.Assertions.assertNotEquals(403, status);
    }
}
