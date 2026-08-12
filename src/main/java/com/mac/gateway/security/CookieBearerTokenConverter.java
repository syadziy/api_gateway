package com.mac.gateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class CookieBearerTokenConverter implements ServerAuthenticationConverter {

    private final ServerBearerTokenAuthenticationConverter headerConverter =
            new ServerBearerTokenAuthenticationConverter();
    private final String cookieName;

    public CookieBearerTokenConverter(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return headerConverter.convert(exchange).switchIfEmpty(Mono.justOrEmpty(
                        exchange.getRequest().getCookies().getFirst(cookieName))
                .map(HttpCookie::getValue)
                .filter(value -> !value.isBlank())
                .map(BearerTokenAuthenticationToken::new));
    }
}
