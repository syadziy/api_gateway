package com.mac.gateway.service.impl;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.service.RateLimitKeyService;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class RateLimitKeyServiceImpl implements RateLimitKeyService {

    private static final Pattern SAFE_CLIENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private final GatewayProperties properties;

    public RateLimitKeyServiceImpl(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String routeId = routeId(exchange);
        return exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .map(name -> routeId + ":user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> routeId + ":client:" + clientIdentity(exchange)));
    }

    private String clientIdentity(ServerWebExchange exchange) {
        String clientId = exchange.getRequest().getHeaders().getFirst(properties.http().clientIdHeader());
        if (clientId != null && SAFE_CLIENT_ID.matcher(clientId).matches()) {
            return clientId;
        }
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null ? "anonymous" : address.getAddress().getHostAddress();
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "unmatched" : route.getId();
    }
}
