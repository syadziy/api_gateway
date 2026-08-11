package com.mac.gateway.entities.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID eventId,
        String sourceSystem,
        Instant occurredAt,
        String actorId,
        String actorName,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String traceId,
        String clientIp,
        Map<String, Object> metadata) {}
