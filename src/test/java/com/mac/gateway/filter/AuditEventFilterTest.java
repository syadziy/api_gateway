package com.mac.gateway.filter;

import static com.mac.gateway.TestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mac.gateway.entities.dto.AuditEvent;
import com.mac.gateway.service.AuditEventPublisher;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AuditEventFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void bypassesAuditImplementationWhenDisabled() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(false, true),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks"));

        filter.filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }).block();

        verifyNoInteractions(publisher);
    }

    @Test
    void bypassesWebSocketTrafficWithoutSkippingTheGatewayChain() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws/alerts")
                        .header("Upgrade", "websocket")
                        .header("Connection", "Upgrade"));
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.filter(exchange, current -> {
            chainInvoked.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(publisher);
    }

    @Test
    void publishesAuthenticatedRequestWithJwtActorAndNormalizedPath() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Jwt token = Jwt.withTokenValue("redacted").header("alg", "none")
                .subject(userId.toString()).claim("username", "owner")
                .claim("tenant_id", tenantId.toString()).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(token, List.of(), "owner");
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.patch("/api/v1/tenants/" + resourceId + "/token-policy")
                        .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 1234)));
        exchange.getAttributes().put(GatewayLogFieldsAttribute.TRACE_ID, "trace-1");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("usermanagement"));
        exchange = exchange.mutate().principal(Mono.just(authentication)).build();

        filter.filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }).block();

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue()).satisfies(value -> {
            assertThat(value.actorId()).isEqualTo("owner");
            assertThat(value.actorName()).isEqualTo("owner");
            assertThat(value.action()).isEqualTo("USERMANAGEMENT_UPDATE");
            assertThat(value.outcome()).isEqualTo("SUCCESS");
            assertThat(value.traceId()).isEqualTo("trace-1");
            assertThat(value.metadata()).containsEntry("tenantId", tenantId.toString())
                    .containsEntry("httpPath", "/api/v1/tenants/{id}/token-policy");
        });
    }

    @Test
    void fallsBackToAuthenticatedPrincipalWhenUsernameClaimIsMissing() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        Jwt token = Jwt.withTokenValue("redacted").header("alg", "none")
                .subject("fallback-user").build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                token, List.of(), "fallback-user");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks"))
                .mutate().principal(Mono.just(authentication)).build();

        filter.filter(exchange, chain -> Mono.empty()).block();

        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.actorId().equals("fallback-user") && event.actorName().equals("fallback-user")));
    }

    @Test
    void publishesDeniedAnonymousUnmatchedRequest() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tasks/123"));

        filter.filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return Mono.empty();
        }).block();

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().actorId()).isEqualTo("unknown-user");
        assertThat(event.getValue().action()).isEqualTo("UNMATCHED_READ");
        assertThat(event.getValue().outcome()).isEqualTo("DENIED");
        assertThat(event.getValue().metadata()).containsEntry("httpPath", "/api/v1/tasks/{id}");
        assertThat(filter.getOrder()).isEqualTo(-140);
    }

    @Test
    void supportsNonJwtAuthenticationAndPathUtilities() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/v1/tasks/42"))
                .mutate().principal(Mono.just(new TestingAuthenticationToken("client", "", "ROLE_USER"))).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("scheduler"));
        filter.filter(exchange, chain -> Mono.empty()).block();

        assertThat(AuditEventFilter.normalizePath("/x/42/" + UUID.randomUUID())).isEqualTo("/x/{id}/{id}");
        assertThat(AuditEventFilter.action("audit-log", org.springframework.http.HttpMethod.DELETE))
                .isEqualTo("AUDIT_LOG_DELETE");
        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.actorId().equals("client") && event.actorName().equals("client")));
    }

    @Test
    void publishesLoginWithUsernameFromCapturedRequestBody() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditEventFilter filter = new AuditEventFilter(publisher, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), actorResolver());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));
        exchange.getAttributes().put(GatewayLogFieldsAttribute.REQUEST_USERNAME, "login.owner");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("usermanagement"));

        filter.filter(exchange, chain -> Mono.empty()).block();

        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.action().equals("AUTH_LOGIN")
                        && event.actorId().equals("login.owner")
                        && event.actorName().equals("login.owner")));
    }

    private static Route route(String id) {
        return Route.async().id(id).uri(URI.create("http://service")).predicate(value -> true).build();
    }

    private static GatewayActorResolver actorResolver() {
        return new GatewayActorResolver(new tools.jackson.databind.ObjectMapper());
    }
}
