package com.mac.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

    @Test
    void logsMatchedSuccessAndUnmatchedFailureWithoutRawPath() {
        var matched = MockServerWebExchange.from(MockServerHttpRequest.get("/users/secret-id")
                .header("Content-Length", "12"));
        matched.getAttributes().put(GatewayLogFieldsAttribute.TRACE_ID, "trace");
        matched.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("route-a").uri(URI.create("http://service-a"))
                        .predicate(value -> true).build());
        filter.filter(matched, exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            exchange.getResponse().getHeaders().setContentLength(4);
            return Mono.empty();
        }).block();

        var unmatched = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        filter.filter(unmatched, exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return Mono.empty();
        }).block();

        assertThat(filter.getOrder()).isEqualTo(-150);
    }
}
