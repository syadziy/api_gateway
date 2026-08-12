package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.utils.constant.GatewayHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class GatewayActorResolver {

    private final ObjectMapper objectMapper;

    public GatewayActorResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Actor> resolve(ServerWebExchange exchange, GatewayProperties properties) {
        return exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .map(authentication -> fromAuthentication(authentication, properties))
                .defaultIfEmpty(resolveFromRequest(exchange, properties));
    }

    private Actor fromAuthentication(Authentication authentication, GatewayProperties properties) {
        if (authentication instanceof JwtAuthenticationToken jwt && authentication.isAuthenticated()) {
            String username = firstNonBlank(
                    jwt.getToken().getClaimAsString("username"),
                    jwt.getToken().getClaimAsString("preferred_username"),
                    jwt.getName(),
                    authentication.getName(),
                    properties.audit().fallbackActorId());
            return new Actor(username, jwt.getToken().getClaimAsString("tenant_id"));
        }
        return new Actor(firstNonBlank(authentication.getName(), properties.audit().fallbackActorId()), null);
    }

    private Actor resolveFromRequest(ServerWebExchange exchange, GatewayProperties properties) {
        String headerActor = firstNonBlank(
                exchange.getAttribute(GatewayLogFieldsAttribute.REQUEST_USERNAME),
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.AUTHENTICATED_USER),
                exchange.getRequest().getHeaders().getFirst("X-User-Name"),
                exchange.getRequest().getHeaders().getFirst("X-Username"));
        if (headerActor != null) {
            return new Actor(headerActor, exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"));
        }

        String bearerToken = bearerToken(exchange.getRequest().getHeaders());
        if (bearerToken != null) {
            Actor decoded = decodeJwtClaims(bearerToken, properties.audit().fallbackActorId());
            if (decoded != null) {
                return decoded;
            }
        }

        return new Actor(properties.audit().fallbackActorId(), null);
    }

    private Actor decodeJwtClaims(String token, String fallbackActorId) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode claims = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            String username = firstNonBlank(
                    text(claims, "username"),
                    text(claims, "preferred_username"),
                    text(claims, "sub"),
                    fallbackActorId);
            String tenantId = text(claims, "tenant_id");
            return new Actor(username, tenantId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String bearerToken(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String padBase64(String value) {
        int padding = (4 - (value.length() % 4)) % 4;
        return value + "=".repeat(padding);
    }

    public record Actor(String username, String tenantId) {
    }
}
