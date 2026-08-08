package com.mac.gateway.filter;

import com.mac.gateway.utils.constant.GatewayLogFields;
import com.mac.gateway.utils.logging.StructuredLog;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAt = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> log(exchange, startedAt));
    }

    private void log(ServerWebExchange exchange, long startedAt) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int statusCode = status == null ? 200 : status.value();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        URI routeUri = route == null ? null : route.getUri();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(GatewayLogFields.TRACE_ID,
                exchange.getAttributeOrDefault(GatewayLogFieldsAttribute.TRACE_ID, "unknown"));
        fields.put(GatewayLogFields.EVENT_ACTION, "gatewayRequest");
        fields.put(GatewayLogFields.EVENT_OUTCOME, statusCode >= 400 ? "failure" : "success");
        fields.put(GatewayLogFields.EVENT_DATASET, "api-gateway.access");
        fields.put(GatewayLogFields.EVENT_DURATION,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        fields.put(GatewayLogFields.HTTP_METHOD, exchange.getRequest().getMethod().name());
        fields.put(GatewayLogFields.HTTP_STATUS, statusCode);
        fields.put(GatewayLogFields.HTTP_REQUEST_BYTES,
                Math.max(0, exchange.getRequest().getHeaders().getContentLength()));
        fields.put(GatewayLogFields.HTTP_RESPONSE_BYTES,
                Math.max(0, exchange.getResponse().getHeaders().getContentLength()));
        fields.put(GatewayLogFields.ROUTE_ID, route == null ? "unmatched" : route.getId());
        fields.put(GatewayLogFields.UPSTREAM, routeUri == null || routeUri.getHost() == null
                ? "unmatched" : routeUri.getHost());
        StructuredLog.info(LOG, "Gateway request completed", fields);
    }

    @Override
    public int getOrder() {
        return -150;
    }
}
