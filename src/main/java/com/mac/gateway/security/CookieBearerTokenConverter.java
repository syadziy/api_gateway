package com.mac.gateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
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
        if (isPublicCredentialEndpoint(exchange)) {
            return Mono.empty();
        }
        return headerConverter.convert(exchange).switchIfEmpty(Mono.justOrEmpty(
                        exchange.getRequest().getCookies().getFirst(cookieName))
                .map(HttpCookie::getValue)
                .filter(value -> !value.isBlank())
                .map(BearerTokenAuthenticationToken::new));
    }

    private static boolean isPublicCredentialEndpoint(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();
        return method == HttpMethod.POST && ("/api/v1/auth/login".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || "/api/v1/tenants".equals(path));
    }
}
