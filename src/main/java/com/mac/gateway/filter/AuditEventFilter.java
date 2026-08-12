package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.entities.dto.AuditEvent;
import com.mac.gateway.service.AuditEventPublisher;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
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
    private final GatewayActorResolver actorResolver;

    public AuditEventFilter(AuditEventPublisher publisher, GatewayProperties properties, Clock clock,
            GatewayActorResolver actorResolver) {
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.actorResolver = actorResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.audit().enabled() || isWebSocket(exchange)) {
            return chain.filter(exchange);
        }
        return actorResolver.resolve(exchange, properties)
                .flatMap(actor -> chain.filter(exchange)
                        .doFinally(signal -> publish(exchange, actor)));
    }

    private void publish(ServerWebExchange exchange, GatewayActorResolver.Actor actor) {
        UUID eventId = UUID.randomUUID();
        String traceId = exchange.getAttributeOrDefault(GatewayLogFieldsAttribute.TRACE_ID, eventId.toString());
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int statusCode = status == null ? 200 : status.value();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unmatched" : route.getId();
        String normalizedPath = normalizePath(exchange.getRequest().getPath().value());
        HttpMethod httpMethod = exchange.getRequest().getMethod();
        String permission = requiredPermission(httpMethod, normalizedPath);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("httpMethod", httpMethod.name());
        metadata.put("httpPath", normalizedPath);
        metadata.put("httpStatus", statusCode);
        metadata.put("routeId", routeId);
        if (permission != null) {
            metadata.put("requiredPermission", permission);
        }
        if (actor.tenantId() != null) {
            metadata.put("tenantId", actor.tenantId());
        }
        publisher.publish(new AuditEvent(
                eventId,
                properties.audit().sourceSystem(),
                clock.instant(),
                actor.username(),
                actor.username(),
                action(routeId, httpMethod, normalizedPath),
                routeId.toUpperCase(Locale.ROOT),
                null,
                statusCode < 400 ? "SUCCESS" : statusCode == 401 || statusCode == 403 ? "DENIED" : "FAILURE",
                traceId,
                clientIp(exchange),
                Map.copyOf(metadata)));
    }

    static String normalizePath(String path) {
        String withoutUuid = UUID_SEGMENT.matcher(path).replaceAll("{id}");
        return NUMBER_SEGMENT.matcher(withoutUuid).replaceAll("{id}");
    }

    static String action(String routeId, HttpMethod method) {
        return action(routeId, method, null);
    }

    static String action(String routeId, HttpMethod method, String path) {
        if (method == HttpMethod.POST && "/api/v1/auth/login".equals(path)) {
            return "AUTH_LOGIN";
        }
        if (method == HttpMethod.POST && "/api/v1/auth/logout".equals(path)) {
            return "AUTH_LOGOUT";
        }
        if ((method == HttpMethod.GET || method == HttpMethod.HEAD)
                && "/api/v1/auth/session".equals(path)) {
            return "AUTH_SESSION_READ";
        }
        if (method == HttpMethod.POST && "/api/v1/tenants".equals(path)) {
            return "TENANT_REGISTER";
        }
        String permission = requiredPermission(method, path);
        if (permission != null) {
            return permissionAction(permission, path);
        }
        String operation = switch (method.name()) {
            case "GET", "HEAD" -> "READ";
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.name();
        };
        return routeId.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + operation;
    }

    private static String permissionAction(String permission, String path) {
        if ("gateway-log.read".equals(permission)
                && isPathWithin(path, "/api/v1/gateway-logs")) {
            return "LOG_READ";
        }
        String schedulerResource = schedulerResource(path);
        if (schedulerResource != null && permission.startsWith("scheduler.")) {
            String operation = permission.substring("scheduler.".length());
            return "SCHEDULER_" + schedulerResource + "_" + operation.toUpperCase(Locale.ROOT);
        }
        return permission.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    static String requiredPermission(HttpMethod method, String path) {
        if (method == null || path == null) {
            return null;
        }
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            if ("/api/v1/tenants".equals(path)) {
                return "tenant.view";
            }
            if (path.matches("/api/v1/tenants/[^/]+/users")) {
                return "user.view";
            }
            if (path.matches("/api/v1/tenants/[^/]+/roles")) {
                return "role.view";
            }
            if (path.matches("/api/v1/tenants/[^/]+/permissions")) {
                return "permission.view";
            }
            if (isPathWithin(path, "/api/v1/alert/recipients")) {
                return "alert.read-recipients";
            }
            if (isPathWithin(path, "/api/v1/alert/delivery-history")) {
                return "alert.read-notifications";
            }
            if (isPathWithin(path, "/api/v1/audit-logs")) {
                return "audit.read";
            }
            if (isPathWithin(path, "/api/v1/gateway-logs")) {
                return "gateway-log.read";
            }
            if (isPathWithin(path, "/api/v1/histories") || isSchedulerPath(path)) {
                return "scheduler.read";
            }
        }
        if (method == HttpMethod.PATCH
                && path.matches("/api/v1/tenants/[^/]+/token-policy")) {
            return "tenant.update";
        }
        if (method == HttpMethod.POST && path.matches("/api/v1/tenants/[^/]+/users")) {
            return "user.create";
        }
        if (method == HttpMethod.PUT
                && path.matches("/api/v1/tenants/[^/]+/users/[^/]+/roles")) {
            return "role.assign";
        }
        if (method == HttpMethod.POST && path.matches("/api/v1/tenants/[^/]+/roles")) {
            return "role.create";
        }
        if (method == HttpMethod.PUT
                && path.matches("/api/v1/tenants/[^/]+/roles/[^/]+/permissions")) {
            return "role.edit";
        }
        if (method == HttpMethod.POST && path.matches("/api/v1/tenants/[^/]+/permissions")) {
            return "permission.create";
        }
        if ((method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.DELETE)
                && isPathWithin(path, "/api/v1/alert/recipients")) {
            return "alert.manage-recipients";
        }
        if (method == HttpMethod.POST && isPathWithin(path, "/api/v1/alert")) {
            return "alert.write";
        }
        if (method == HttpMethod.POST && isSchedulerPath(path)) {
            return "scheduler.manage";
        }
        return null;
    }

    private static boolean isSchedulerPath(String path) {
        return isPathWithin(path, "/api/v1/tasks")
                || isPathWithin(path, "/api/v1/task-groups")
                || isPathWithin(path, "/api/v1/schedules");
    }

    private static String schedulerResource(String path) {
        if (path == null) {
            return null;
        }
        if (isPathWithin(path, "/api/v1/histories")) {
            return "HISTORY";
        }
        if (isPathWithin(path, "/api/v1/schedules")) {
            return "SCHEDULE";
        }
        if (isPathWithin(path, "/api/v1/task-groups")) {
            return "TASK_GROUP";
        }
        if (isPathWithin(path, "/api/v1/tasks")) {
            return "TASK";
        }
        return null;
    }

    private static boolean isPathWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean isWebSocket(ServerWebExchange exchange) {
        String upgrade = exchange.getRequest().getHeaders().getFirst(HttpHeaders.UPGRADE);
        return "websocket".equalsIgnoreCase(upgrade)
                || exchange.getRequest().getPath().value().startsWith("/ws/");
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -140;
    }
}
