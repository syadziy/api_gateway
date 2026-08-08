package com.mac.gateway.utils.handler;

import java.time.Instant;

public record GatewayErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp) {}
