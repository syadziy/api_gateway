package com.mac.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.entities.dto.AuditEvent;
import com.mac.gateway.entities.dto.GatewayLogEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

class KafkaEventSerializationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-11T08:48:17.783533329Z");

    @Test
    void serializesAuditEventWithInstantUsingProductionSerializer() {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), "API-GATEWAY", OCCURRED_AT,
                "owner", "owner", "SCHEDULER_READ", "SCHEDULER", null, "SUCCESS",
                "trace-1", "127.0.0.1", Map.of("httpMethod", "GET"));

        String json = serialize("centralized-audit.requested", event);

        assertThat(json).contains("\"occurredAt\":\"2026-08-11T08:48:17.783533329Z\"")
                .contains("\"actorId\":\"owner\"");
    }

    @Test
    void serializesCentralizedLogEventWithInstantUsingProductionSerializer() {
        GatewayLogEvent event = new GatewayLogEvent(UUID.randomUUID(), OCCURRED_AT, "trace-1", "owner",
                "tenant-1", "scheduler", "GET", "/api/v1/tasks", Map.of(), null, 200,
                Map.of(), null, 12, "127.0.0.1", false, false);

        String json = serialize("centralized-log.requested", event);

        assertThat(json).contains("\"occurredAt\":\"2026-08-11T08:48:17.783533329Z\"")
                .contains("\"routeId\":\"scheduler\"");
    }

    private static String serialize(String topic, Object event) {
        try (JacksonJsonSerializer<Object> serializer = new JacksonJsonSerializer<>()) {
            return new String(serializer.serialize(topic, event), StandardCharsets.UTF_8);
        }
    }
}
