package com.mac.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.utils.constant.GatewayHeaders;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AuthenticatedHeadersFilterTest {

    private final AuthenticatedHeadersFilter filter = new AuthenticatedHeadersFilter();

    @Test
    void addsVerifiedIdentityHeaders() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        var authentication = new UsernamePasswordAuthenticationToken("user-1", "n/a", List.of(
                new SimpleGrantedAuthority("SCOPE_b"), new SimpleGrantedAuthority("SCOPE_a")));
        exchange = exchange.mutate().principal(Mono.just(authentication)).build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, value -> { forwarded.set(value); return Mono.empty(); }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(GatewayHeaders.AUTHENTICATED_USER))
                .isEqualTo("user-1");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(GatewayHeaders.AUTHENTICATED_AUTHORITIES))
                .isEqualTo("SCOPE_a,SCOPE_b");
        assertThat(filter.getOrder()).isEqualTo(-100);
    }

    @Test
    void leavesAnonymousRequestUnchanged() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, value -> { forwarded.set(value); return Mono.empty(); }).block();
        assertThat(forwarded.get()).isSameAs(exchange);
    }
}
