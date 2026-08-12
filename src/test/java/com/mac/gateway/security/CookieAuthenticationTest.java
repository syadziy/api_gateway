package com.mac.gateway.security;

import static com.mac.gateway.TestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.filter.CookieTokenRelayFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CookieAuthenticationTest {

    @Test
    void resolvesCookieWhenAuthorizationHeaderIsMissing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks")
                .cookie(ResponseCookie.from("ACCESS_TOKEN", "cookie-token").build()));

        assertThat(new CookieBearerTokenConverter("ACCESS_TOKEN").convert(exchange).block())
                .isInstanceOfSatisfying(BearerTokenAuthenticationToken.class,
                        token -> assertThat(token.getToken()).isEqualTo("cookie-token"));
    }

    @Test
    void explicitBearerHeaderTakesPrecedenceOverCookie() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer header-token")
                .cookie(ResponseCookie.from("ACCESS_TOKEN", "cookie-token").build()));

        assertThat(new CookieBearerTokenConverter("ACCESS_TOKEN").convert(exchange).block())
                .isInstanceOfSatisfying(BearerTokenAuthenticationToken.class,
                        token -> assertThat(token.getToken()).isEqualTo("header-token"));
    }

    @Test
    void relaysCookieAsBearerHeaderWithoutDuplicatingTokenDownstream() {
        CookieTokenRelayFilter filter = new CookieTokenRelayFilter(properties());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks")
                .cookie(ResponseCookie.from("ACCESS_TOKEN", "cookie-token").build())
                .cookie(ResponseCookie.from("theme", "dark").build()));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, current -> {
            forwarded.set(current);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer cookie-token");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE))
                .isEqualTo("theme=dark")
                .doesNotContain("ACCESS_TOKEN", "cookie-token");
        assertThat(filter.getOrder()).isEqualTo(-90);
    }

    @Test
    void preservesExplicitBearerHeaderWhileRemovingAuthCookie() {
        CookieTokenRelayFilter filter = new CookieTokenRelayFilter(properties());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer header-token")
                .cookie(ResponseCookie.from("ACCESS_TOKEN", "cookie-token").build()));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, current -> {
            forwarded.set(current);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer header-token");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE)).isNull();
    }
}
