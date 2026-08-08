package com.mac.gateway.utils.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.TestFixtures;
import com.mac.gateway.filter.GatewayLogFieldsAttribute;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.json.JsonMapper;

class ReactiveErrorWriterTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ReactiveErrorWriter writer = new ReactiveErrorWriter(
            mapper, TestFixtures.properties(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void writesJsonWithExistingTrace() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        exchange.getAttributes().put(GatewayLogFieldsAttribute.TRACE_ID, "trace-1");

        writer.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Route was not found").block();

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(mapper.readTree(body).get("traceId").asText()).isEqualTo("trace-1");
        assertThat(exchange.getResponse().getHeaders().getContentLength())
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void generatesTraceAndDoesNothingWhenCommitted() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        writer.write(exchange, HttpStatus.BAD_REQUEST, "BAD", "Invalid request").block();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isNotBlank();

        var committed = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        committed.getResponse().setComplete().block();
        writer.write(committed, HttpStatus.BAD_REQUEST, "BAD", "Invalid request").block();
        assertThat(committed.getResponse().getBodyAsString().block()).isEmpty();
    }
}
