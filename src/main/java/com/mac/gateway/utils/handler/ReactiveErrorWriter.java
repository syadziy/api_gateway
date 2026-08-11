package com.mac.gateway.utils.handler;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.filter.GatewayLogFieldsAttribute;
import java.time.Clock;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReactiveErrorWriter {

    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final Clock clock;

    public ReactiveErrorWriter(ObjectMapper objectMapper, GatewayProperties properties, Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        String traceId = exchange.getAttribute(GatewayLogFieldsAttribute.TRACE_ID);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
            exchange.getResponse().getHeaders().set(properties.http().correlationHeader(), traceId);
        }
        byte[] bytes = objectMapper.writeValueAsBytes(
                new GatewayErrorResponse(code, message, null, null, traceId, clock.instant()));
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
