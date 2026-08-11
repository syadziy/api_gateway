package com.mac.gateway.utils.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.TestFixtures;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class GatewayExceptionHandlerTest {

    @Test
    void connectionFailureUsesSdkCompatibleServiceUnavailableResponse() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var writer = new ReactiveErrorWriter(mapper, TestFixtures.properties(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/alert/delivery-history"));

        new GatewayExceptionHandler(writer).handle(exchange,
                new IllegalStateException("upstream", new ConnectException("connection refused"))).block();

        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body.get("code").asText()).isEqualTo("RC-503");
        assertThat(body.get("message").asText()).isEqualTo("service unavailable");
        assertThat(body.has("data")).isTrue();
        assertThat(body.has("errors")).isTrue();
        assertThat(body.get("traceId").asText()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("errors")
    void mapsSafeGatewayErrors(Throwable error, HttpStatus expectedStatus, String expectedMessage) {
        var writer = new ReactiveErrorWriter(JsonMapper.builder().findAndAddModules().build(),
                TestFixtures.properties(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var handler = new GatewayExceptionHandler(writer);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        handler.handle(exchange, error).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expectedStatus);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains(expectedMessage);
    }

    static Stream<Arguments> errors() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");
        breaker.transitionToOpenState();
        return Stream.of(
                Arguments.of(new ResponseStatusException(HttpStatus.NOT_FOUND), HttpStatus.NOT_FOUND,
                        "Route was not found"),
                Arguments.of(new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT), HttpStatus.I_AM_A_TEAPOT,
                        "Request was rejected"),
                Arguments.of(new TimeoutException(), HttpStatus.GATEWAY_TIMEOUT, "Upstream service timed out"),
                Arguments.of(CallNotPermittedException.createCallNotPermittedException(breaker),
                        HttpStatus.SERVICE_UNAVAILABLE, "service unavailable"),
                Arguments.of(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("test")),
                        HttpStatus.SERVICE_UNAVAILABLE, "service unavailable"),
                Arguments.of(new RedisConnectionFailureException("redis"), HttpStatus.SERVICE_UNAVAILABLE,
                        "service unavailable"),
                Arguments.of(new ConnectException("connection refused"), HttpStatus.SERVICE_UNAVAILABLE,
                        "service unavailable"),
                Arguments.of(new IllegalStateException("upstream", new ConnectException("connection refused")),
                        HttpStatus.SERVICE_UNAVAILABLE, "service unavailable"),
                Arguments.of(new UnknownHostException("upstream"), HttpStatus.SERVICE_UNAVAILABLE,
                        "service unavailable"),
                Arguments.of(new IllegalStateException("secret"), HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected gateway error occurred"));
    }
}
