package com.mac.gateway.config;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.service.RateLimitKeyService;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class RouteConfig {

    @Bean
    RedisRateLimiter redisRateLimiter(GatewayProperties properties) {
        return new RedisRateLimiter(
                properties.rateLimit().replenishRate(),
                properties.rateLimit().burstCapacity(),
                properties.rateLimit().requestedTokens());
    }

    @Bean
    KeyResolver gatewayKeyResolver(RateLimitKeyService service) {
        return service::resolve;
    }

    @Bean
    RouteLocator gatewayRoutes(
            RouteLocatorBuilder builder,
            GatewayProperties properties,
            RedisRateLimiter rateLimiter,
            KeyResolver keyResolver) {
        long responseTimeoutMillis = properties.http().responseTimeout().toMillis();
        RouteLocatorBuilder.Builder routes = builder.routes();
        if (properties.canary().enabled()) {
            int canaryWeight = properties.canary().weight();
            routes.route("centralized-alert-stable", route -> route
                            .path("/api/v1/alert", "/api/v1/alert/**")
                            .and().weight("centralized-alert", 100 - canaryWeight)
                            .filters(filters -> common(filters, "centralized-alert", properties,
                                    rateLimiter, keyResolver).metadata("response-timeout", responseTimeoutMillis))
                            .uri(properties.routes().alert()))
                    .route("centralized-alert-canary", route -> route
                            .path("/api/v1/alert", "/api/v1/alert/**")
                            .and().weight("centralized-alert", canaryWeight)
                            .filters(filters -> common(filters, "centralized-alert", properties,
                                    rateLimiter, keyResolver).metadata("response-timeout", responseTimeoutMillis))
                            .uri(properties.canary().alert()));
        } else {
            routes.route("centralized-alert", route -> route
                    .path("/api/v1/alert", "/api/v1/alert/**")
                    .filters(filters -> common(filters, "centralized-alert", properties, rateLimiter, keyResolver)
                            .metadata("response-timeout", responseTimeoutMillis))
                    .uri(properties.routes().alert()));
        }
        return routes.route("centralized-alert-websocket", route -> route
                        .path("/ws/alerts")
                        .uri(properties.routes().alertWebSocket()))
                .route("scheduler", route -> route
                        .path("/api/v1/tasks/**", "/api/v1/task-groups/**", "/api/v1/schedules/**",
                                "/api/v1/histories/**")
                        .filters(filters -> common(filters, "scheduler", properties, rateLimiter, keyResolver)
                                .metadata("response-timeout", responseTimeoutMillis))
                        .uri(properties.routes().scheduler()))
                .route("audit-log", route -> route
                        .path("/api/v1/audit-logs", "/api/v1/audit-logs/**")
                        .filters(filters -> common(filters, "audit-log", properties, rateLimiter, keyResolver)
                                .metadata("response-timeout", responseTimeoutMillis))
                        .uri(properties.routes().audit()))
                .route("usermanagement", route -> route
                        .path("/api/v1/auth/**", "/api/v1/tenants", "/api/v1/tenants/**")
                        .filters(filters -> common(filters, "usermanagement", properties, rateLimiter, keyResolver)
                                .metadata("response-timeout", responseTimeoutMillis))
                        .uri(properties.routes().usermanagement()))
                .build();
    }

    private org.springframework.cloud.gateway.route.builder.GatewayFilterSpec common(
            org.springframework.cloud.gateway.route.builder.GatewayFilterSpec filters,
            String circuitBreakerName,
            GatewayProperties properties,
            RedisRateLimiter rateLimiter,
            KeyResolver keyResolver) {
        return filters
                .setRequestSize(properties.http().maxRequestSize())
                .requestRateLimiter(config -> config
                        .setRateLimiter(rateLimiter)
                        .setKeyResolver(keyResolver)
                        .setDenyEmptyKey(true)
                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                .circuitBreaker(config -> config.setName(circuitBreakerName))
                .retry(config -> config
                        .setRetries(2)
                        .setMethods(HttpMethod.GET, HttpMethod.HEAD)
                        .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
                                HttpStatus.GATEWAY_TIMEOUT)
                        .setBackoff(Duration.ofMillis(100), Duration.ofSeconds(1), 2, false))
                .secureHeaders()
                .dedupeResponseHeader("Access-Control-Allow-Credentials Access-Control-Allow-Origin", "RETAIN_FIRST");
    }
}
