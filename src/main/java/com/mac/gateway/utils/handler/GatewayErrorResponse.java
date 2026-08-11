package com.mac.gateway.utils.handler;

import java.time.Instant;
import java.util.List;

public record GatewayErrorResponse(
        String code,
        String message,
        Object data,
        List<String> errors,
        String traceId,
        Instant timestamp) {}
