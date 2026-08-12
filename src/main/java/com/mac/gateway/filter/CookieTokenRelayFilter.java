package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CookieTokenRelayFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;

    public CookieTokenRelayFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isPublicCredentialEndpoint(exchange)) {
            var request = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(HttpHeaders.AUTHORIZATION);
                removeAuthCookie(exchange, headers);
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        }
        var cookie = exchange.getRequest().getCookies().getFirst(properties.security().authCookieName());
        if (cookie == null || cookie.getValue().isBlank()) {
            return chain.filter(exchange);
        }
        var request = exchange.getRequest().mutate()
                .headers(headers -> {
                    if (headers.getFirst(HttpHeaders.AUTHORIZATION) == null) {
                        headers.setBearerAuth(cookie.getValue());
                    }
                    removeAuthCookie(exchange, headers);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private void removeAuthCookie(ServerWebExchange exchange, HttpHeaders headers) {
        headers.remove(HttpHeaders.COOKIE);
        String remainingCookies = exchange.getRequest().getCookies().values().stream()
                .flatMap(java.util.Collection::stream)
                .filter(item -> !properties.security().authCookieName().equals(item.getName()))
                .map(item -> item.getName() + "=" + item.getValue())
                .collect(Collectors.joining("; "));
        if (!remainingCookies.isBlank()) {
            headers.set(HttpHeaders.COOKIE, remainingCookies);
        }
    }

    private static boolean isPublicCredentialEndpoint(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();
        return method == HttpMethod.POST && ("/api/v1/auth/login".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || "/api/v1/tenants".equals(path));
    }
}
