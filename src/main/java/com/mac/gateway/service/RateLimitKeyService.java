package com.mac.gateway.service;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public interface RateLimitKeyService {

    Mono<String> resolve(ServerWebExchange exchange);
}
