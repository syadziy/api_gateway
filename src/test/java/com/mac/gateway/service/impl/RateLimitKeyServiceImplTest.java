package com.mac.gateway.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.TestFixtures;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

class RateLimitKeyServiceImplTest {

    private final RateLimitKeyServiceImpl service = new RateLimitKeyServiceImpl(TestFixtures.properties());

    @Test
    void usesAuthenticatedUserAndRoute() {
        var exchange = exchange(MockServerHttpRequest.get("/api/v1/alert").build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("alert").uri("http://alert").predicate(value -> true).build());
        var authentication = new UsernamePasswordAuthenticationToken(
                "user-1", "n/a", List.of(new SimpleGrantedAuthority("SCOPE_alert.write")));
        var authenticated = exchange.mutate().principal(Mono.just(authentication)).build();

        assertThat(service.resolve(authenticated).block()).isEqualTo("alert:user:user-1");
    }

    @Test
    void fallsBackToSafeClientIdThenRemoteAddressThenAnonymous() {
        var client = exchange(MockServerHttpRequest.get("/").header("X-Client-Id", "client:1").build());
        assertThat(service.resolve(client).block()).isEqualTo("unmatched:client:client:1");

        var remote = exchange(MockServerHttpRequest.get("/")
                .header("X-Client-Id", "unsafe client")
                .remoteAddress(new InetSocketAddress("10.0.0.8", 1234)).build());
        assertThat(service.resolve(remote).block()).isEqualTo("unmatched:client:10.0.0.8");

        var anonymous = exchange(MockServerHttpRequest.get("/").build());
        assertThat(service.resolve(anonymous).block()).isEqualTo("unmatched:client:anonymous");
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }
}
