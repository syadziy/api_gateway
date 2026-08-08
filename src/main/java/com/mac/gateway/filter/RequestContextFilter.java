package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.utils.constant.GatewayHeaders;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestContextFilter implements GlobalFilter, Ordered {

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]+");
    private final GatewayProperties properties;

    public RequestContextFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String headerName = properties.http().correlationHeader();
        String traceId = normalize(exchange.getRequest().getHeaders().getFirst(headerName));
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            GatewayHeaders.UNTRUSTED_INBOUND.forEach(headers::remove);
            headers.set(headerName, traceId);
        }).build();
        exchange.getResponse().getHeaders().set(headerName, traceId);
        exchange.getAttributes().put(GatewayLogFieldsAttribute.TRACE_ID, traceId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    private String normalize(String candidate) {
        if (candidate != null
                && candidate.length() <= properties.http().correlationIdMaxLength()
                && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
