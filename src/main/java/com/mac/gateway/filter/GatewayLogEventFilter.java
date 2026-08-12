package com.mac.gateway.filter;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.entities.dto.GatewayLogEvent;
import com.mac.gateway.service.GatewayLogEventPublisher;
import com.mac.gateway.utils.logging.BodySanitizer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayLogEventFilter implements WebFilter, Ordered {
    private static final Set<String> REQUEST_HEADERS = Set.of("content-type", "accept", "user-agent", "x-client-id");
    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "content-length");
    private final GatewayLogEventPublisher publisher;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final GatewayActorResolver actorResolver;

    public GatewayLogEventFilter(GatewayLogEventPublisher publisher, GatewayProperties properties,
            ObjectMapper objectMapper, Clock clock, GatewayActorResolver actorResolver) {
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.actorResolver = actorResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.centralizedLog().enabled() || !exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }
        int limit = Math.toIntExact(properties.centralizedLog().maxBodySize().toBytes());
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        Capture responseCapture = new Capture(limit);
        ServerHttpResponseDecorator response = responseDecorator(exchange, responseCapture);
        return actorResolver.resolve(exchange, properties).flatMap(actor -> captureRequest(exchange, limit).flatMap(requestCapture -> {
            ServerWebExchange decorated = exchange.mutate()
                    .request(new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override public Flux<DataBuffer> getBody() {
                            return requestCapture.bytes.length == 0 ? Flux.empty()
                                    : Flux.just(exchange.getResponse().bufferFactory().wrap(requestCapture.bytes));
                        }
                    }).response(response).build();
            return chain.filter(decorated).doFinally(signal -> publish(
                    decorated, actor, startedAt, startedNanos, requestCapture, responseCapture));
        }));
    }

    private Mono<CaptureResult> captureRequest(ServerWebExchange exchange, int limit) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes); DataBufferUtils.release(buffer);
                    return new CaptureResult(bytes, bytes.length > limit);
                }).defaultIfEmpty(new CaptureResult(new byte[0], false));
    }

    private ServerHttpResponseDecorator responseDecorator(ServerWebExchange exchange, Capture capture) {
        return new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return super.writeWith(Flux.from(body).map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes); DataBufferUtils.release(buffer); capture.append(bytes);
                    return bufferFactory().wrap(bytes);
                }));
            }
        };
    }

    private void publish(ServerWebExchange exchange, GatewayActorResolver.Actor actor, Instant occurredAt, long startedNanos,
            CaptureResult request, Capture response) {
        UUID eventId = UUID.randomUUID();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        int status = exchange.getResponse().getStatusCode() == null ? 200 : exchange.getResponse().getStatusCode().value();
        boolean requestJson = isJson(exchange.getRequest().getHeaders().getContentType());
        boolean responseJson = isJson(exchange.getResponse().getHeaders().getContentType());
        publisher.publish(new GatewayLogEvent(eventId, occurredAt,
                exchange.getAttributeOrDefault(GatewayLogFieldsAttribute.TRACE_ID, eventId.toString()), actor.username(),
                actor.tenantId(), route == null ? "unmatched" : route.getId(), exchange.getRequest().getMethod().name(),
                exchange.getRequest().getPath().value(), selectedHeaders(exchange.getRequest().getHeaders(), REQUEST_HEADERS),
                requestJson ? BodySanitizer.sanitize(objectMapper, limited(request.bytes, properties.centralizedLog().maxBodySize().toBytes())) : null,
                status, selectedHeaders(exchange.getResponse().getHeaders(), RESPONSE_HEADERS),
                responseJson ? BodySanitizer.sanitize(objectMapper, response.bytes()) : null,
                (System.nanoTime() - startedNanos) / 1_000_000, clientIp(exchange), request.truncated,
                response.truncated));
    }

    private static Map<String, String> selectedHeaders(HttpHeaders headers, Set<String> allowed) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> { if (allowed.contains(name.toLowerCase())) result.put(name, String.join(",", values)); });
        return Map.copyOf(result);
    }
    private static boolean isJson(MediaType mediaType) { return mediaType != null && (MediaType.APPLICATION_JSON.includes(mediaType) || mediaType.getSubtype().endsWith("+json")); }
    private static byte[] limited(byte[] value, long max) { if (value.length <= max) return value; return java.util.Arrays.copyOf(value, Math.toIntExact(max)); }
    private static String clientIp(ServerWebExchange exchange) { InetSocketAddress address = exchange.getRequest().getRemoteAddress(); return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress(); }
    @Override public int getOrder() { return -130; }
    private record CaptureResult(byte[] bytes, boolean truncated) {}
    private static final class Capture {
        private final int limit; private final ByteArrayOutputStream stream = new ByteArrayOutputStream(); private boolean truncated;
        private Capture(int limit) { this.limit = limit; }
        private void append(byte[] bytes) { int remaining = limit - stream.size(); if (remaining > 0) stream.write(bytes, 0, Math.min(remaining, bytes.length)); if (bytes.length > remaining) truncated = true; }
        private byte[] bytes() { return stream.toByteArray(); }
    }
}
