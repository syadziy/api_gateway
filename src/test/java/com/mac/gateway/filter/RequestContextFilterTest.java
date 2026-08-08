package com.mac.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.TestFixtures;
import com.mac.gateway.utils.constant.GatewayHeaders;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class RequestContextFilterTest {

    private final RequestContextFilter filter = new RequestContextFilter(TestFixtures.properties());

    @Test
    void keepsValidCorrelationAndRemovesUntrustedHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header("X-Correlation-Id", "trace-123")
                .header("X-Forwarded-For", "spoofed")
                .header(GatewayHeaders.AUTHENTICATED_USER, "spoofed-user"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, value -> {
            forwarded.set(value);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("trace-123");
        assertThat(forwarded.get().getRequest().getHeaders().containsHeader("X-Forwarded-For")).isFalse();
        assertThat(forwarded.get().getRequest().getHeaders().containsHeader(GatewayHeaders.AUTHENTICATED_USER))
                .isFalse();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isEqualTo("trace-123");
        assertThat(filter.getOrder()).isEqualTo(-200);
    }

    @Test
    void replacesMissingUnsafeOrOversizedCorrelationId() {
        for (String value : new String[] {null, "bad value", "x".repeat(65)}) {
            var request = MockServerHttpRequest.get("/");
            if (value != null) {
                request.header("X-Correlation-Id", value);
            }
            var exchange = MockServerWebExchange.from(request.build());
            AtomicReference<String> trace = new AtomicReference<>();
            filter.filter(exchange, forwarded -> {
                trace.set(forwarded.getRequest().getHeaders().getFirst("X-Correlation-Id"));
                return Mono.empty();
            }).block();
            assertThat(trace.get()).matches("[0-9a-f-]{36}");
        }
    }
}
