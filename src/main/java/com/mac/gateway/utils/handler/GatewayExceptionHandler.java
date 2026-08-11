package com.mac.gateway.utils.handler;

import com.mac.gateway.filter.GatewayLogFieldsAttribute;
import com.mac.gateway.utils.constant.GatewayLogFields;
import com.mac.gateway.utils.logging.StructuredLog;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayExceptionHandler implements WebExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private final ReactiveErrorWriter writer;

    public GatewayExceptionHandler(ReactiveErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        ErrorMapping mapping = map(exception);
        log(exchange, exception, mapping);
        return writer.write(exchange, mapping.status(), mapping.code(), mapping.message());
    }

    private void log(ServerWebExchange exchange, Throwable exception, ErrorMapping mapping) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(GatewayLogFields.TRACE_ID,
                exchange.getAttributeOrDefault(GatewayLogFieldsAttribute.TRACE_ID, "unknown"));
        fields.put(GatewayLogFields.EVENT_ACTION, "gatewayException");
        fields.put(GatewayLogFields.EVENT_OUTCOME, "failure");
        fields.put(GatewayLogFields.EVENT_DATASET, "api-gateway.error");
        fields.put(GatewayLogFields.HTTP_STATUS, mapping.status().value());
        fields.put("error.type", exception.getClass().getSimpleName());
        if (mapping.status().is5xxServerError()) {
            StructuredLog.error(LOG, "Gateway request failed", fields, exception);
        } else {
            StructuredLog.info(LOG, "Gateway request rejected", fields);
        }
    }

    private ErrorMapping map(Throwable exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            return status == null
                    ? internalError()
                    : new ErrorMapping(status, "GATEWAY_" + status.value(), safeMessage(status));
        }
        if (exception instanceof TimeoutException || exception instanceof QueryTimeoutException) {
            return new ErrorMapping(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT", "Upstream service timed out");
        }
        if (exception instanceof CallNotPermittedException
                || exception instanceof BulkheadFullException
                || exception instanceof RedisConnectionFailureException
                || exception instanceof DataAccessResourceFailureException
                || hasCause(exception, ConnectException.class)
                || hasCause(exception, NoRouteToHostException.class)
                || hasCause(exception, UnknownHostException.class)
                || hasCause(exception, UnresolvedAddressException.class)) {
            return new ErrorMapping(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RC-503",
                    "service unavailable");
        }
        return internalError();
    }

    private ErrorMapping internalError() {
        return new ErrorMapping(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "GATEWAY_INTERNAL_ERROR",
                "An unexpected gateway error occurred");
    }

    private String safeMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Invalid request";
            case UNAUTHORIZED -> "Authentication is required";
            case FORBIDDEN -> "Access is denied";
            case NOT_FOUND -> "Route was not found";
            case TOO_MANY_REQUESTS -> "Rate limit exceeded";
            case SERVICE_UNAVAILABLE -> "service unavailable";
            case GATEWAY_TIMEOUT -> "Upstream service timed out";
            default -> status.is4xxClientError() ? "Request was rejected" : "Upstream request failed";
        };
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current == current.getCause()) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ErrorMapping(HttpStatus status, String code, String message) {}
}
