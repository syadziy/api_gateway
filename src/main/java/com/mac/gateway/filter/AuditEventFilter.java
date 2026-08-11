package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.entities.dto.AuditEvent;
import com.mac.gateway.service.AuditEventPublisher;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuditEventFilter implements GlobalFilter, Ordered {

    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "(?i)(?<=/)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?=/|$)");
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("(?<=/)\\d+(?=/|$)");
    private final AuditEventPublisher publisher;
    private final GatewayProperties properties;
    private final Clock clock;

    public AuditEventFilter(AuditEventPublisher publisher, GatewayProperties properties, Clock clock) {
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.audit().enabled()) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .map(this::actor)
                .defaultIfEmpty(Actor.anonymous(properties.audit().fallbackActorId()))
                .flatMap(actor -> chain.filter(exchange)
                        .doFinally(signal -> publish(exchange, actor)));
    }

    private void publish(ServerWebExchange exchange, Actor actor) {
        UUID eventId = UUID.randomUUID();
        String traceId = exchange.getAttributeOrDefault(GatewayLogFieldsAttribute.TRACE_ID, eventId.toString());
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int statusCode = status == null ? 200 : status.value();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unmatched" : route.getId();
        String normalizedPath = normalizePath(exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();
        Map<String, Object> metadata = actor.tenantId() == null
                ? Map.of("httpMethod", method, "httpPath", normalizedPath,
                        "httpStatus", statusCode, "routeId", routeId)
                : Map.of("httpMethod", method, "httpPath", normalizedPath,
                        "httpStatus", statusCode, "routeId", routeId, "tenantId", actor.tenantId());
        publisher.publish(new AuditEvent(
                eventId,
                properties.audit().sourceSystem(),
                clock.instant(),
                actor.id(),
                actor.name(),
                action(routeId, exchange.getRequest().getMethod()),
                routeId.toUpperCase(Locale.ROOT),
                null,
                statusCode < 400 ? "SUCCESS" : statusCode == 401 || statusCode == 403 ? "DENIED" : "FAILURE",
                traceId,
                clientIp(exchange),
                metadata));
    }

    private Actor actor(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt && authentication.isAuthenticated()) {
            String username = nonBlank(
                    jwt.getToken().getClaimAsString("username"), authentication.getName());
            String tenantId = jwt.getToken().getClaimAsString("tenant_id");
            return new Actor(username, username, tenantId);
        }
        return authentication.isAuthenticated()
                ? new Actor(nonBlank(authentication.getName(), properties.audit().fallbackActorId()),
                        authentication.getName(), null)
                : Actor.anonymous(properties.audit().fallbackActorId());
    }

    static String normalizePath(String path) {
        String withoutUuid = UUID_SEGMENT.matcher(path).replaceAll("{id}");
        return NUMBER_SEGMENT.matcher(withoutUuid).replaceAll("{id}");
    }

    static String action(String routeId, HttpMethod method) {
        String operation = switch (method.name()) {
            case "GET", "HEAD" -> "READ";
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.name();
        };
        return routeId.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + operation;
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public int getOrder() {
        return -140;
    }

    private record Actor(String id, String name, String tenantId) {
        private static Actor anonymous(String fallbackId) {
            return new Actor(fallbackId, null, null);
        }
    }
}
