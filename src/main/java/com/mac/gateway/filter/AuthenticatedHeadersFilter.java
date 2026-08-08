package com.mac.gateway.filter;

import com.mac.gateway.utils.constant.GatewayHeaders;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthenticatedHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .filter(Authentication::isAuthenticated)
                .map(authentication -> {
                    String authorities = authentication.getAuthorities().stream()
                            .map(Object::toString)
                            .sorted()
                            .collect(Collectors.joining(","));
                    var request = exchange.getRequest().mutate().headers(headers -> {
                        headers.set(GatewayHeaders.AUTHENTICATED_USER, authentication.getName());
                        headers.set(GatewayHeaders.AUTHENTICATED_AUTHORITIES, authorities);
                    }).build();
                    return exchange.mutate().request(request).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
