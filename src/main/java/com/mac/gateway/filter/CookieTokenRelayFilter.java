package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
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
        if (exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }
        var cookie = exchange.getRequest().getCookies().getFirst(properties.security().authCookieName());
        if (cookie == null || cookie.getValue().isBlank()) {
            return chain.filter(exchange);
        }
        var request = exchange.getRequest().mutate()
                .headers(headers -> headers.setBearerAuth(cookie.getValue()))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
