package com.mac.gateway.filter;

import static com.mac.gateway.TestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mac.gateway.entities.dto.GatewayLogEvent;
import com.mac.gateway.service.GatewayLogEventPublisher;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class GatewayLogEventFilterTest {
    @Test
    void bypassesCentralizedLoggingWhenDisabled() {
        GatewayLogEventPublisher publisher = mock(GatewayLogEventPublisher.class);
        GatewayLogEventFilter filter = new GatewayLogEventFilter(publisher, properties(true, false), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks"));

        filter.filter(exchange, current -> Mono.empty()).block();

        verifyNoInteractions(publisher);
    }

    @Test
    void capturesSanitizedJsonRequestAndResponse() {
        GatewayLogEventPublisher publisher = mock(GatewayLogEventPublisher.class);
        GatewayLogEventFilter filter = new GatewayLogEventFilter(publisher, properties(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
        var request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer hidden")
                .body("{\"username\":\"owner\",\"password\":\"hidden\"}");
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayLogFieldsAttribute.TRACE_ID, "trace-1");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("usermanagement").uri(URI.create("http://service")).predicate(value -> true).build());
        Jwt jwt = Jwt.withTokenValue("redacted").header("alg", "none").subject("id")
                .claim("username", "owner").claim("tenant_id", "tenant-1").build();
        exchange = exchange.mutate().principal(Mono.just(new JwtAuthenticationToken(jwt, List.of(), "owner"))).build();

        filter.filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            current.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return current.getResponse().writeWith(Mono.just(current.getResponse().bufferFactory()
                    .wrap("{\"accessToken\":\"hidden\",\"ok\":true}".getBytes())));
        }).block();

        ArgumentCaptor<GatewayLogEvent> captor = ArgumentCaptor.forClass(GatewayLogEvent.class);
        verify(publisher).publish(captor.capture());
        GatewayLogEvent event = captor.getValue();
        assertThat(event.actor()).isEqualTo("owner");
        assertThat(event.tenantId()).isEqualTo("tenant-1");
        assertThat(event.requestHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        assertThat(event.requestBody().get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(event.responseBody().get("accessToken").asText()).isEqualTo("[REDACTED]");
        assertThat(event.responseStatus()).isEqualTo(200);
        assertThat(filter.getOrder()).isEqualTo(-130);
    }
}
