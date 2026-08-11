package com.mac.gateway.entities.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record GatewayLogEvent(
        UUID eventId,
        Instant occurredAt,
        String traceId,
        String actor,
        String tenantId,
        String routeId,
        String method,
        String path,
        Map<String, String> requestHeaders,
        JsonNode requestBody,
        int responseStatus,
        Map<String, String> responseHeaders,
        JsonNode responseBody,
        long durationMs,
        String clientIp,
        boolean requestTruncated,
        boolean responseTruncated) {}
