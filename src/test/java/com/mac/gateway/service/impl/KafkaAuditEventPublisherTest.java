package com.mac.gateway.service.impl;

import static com.mac.gateway.TestFixtures.properties;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mac.gateway.entities.dto.AuditEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaAuditEventPublisherTest {

    @Test
    void publishesEventAndToleratesAsyncAndSynchronousFailures() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        AuditEvent event = event();
        CompletableFuture<SendResult<String, Object>> success = CompletableFuture.completedFuture(null);
        when(template.send("centralized-audit.requested", event.eventId().toString(), event)).thenReturn(success);
        KafkaAuditEventPublisher publisher = new KafkaAuditEventPublisher(template, properties());
        publisher.publish(event);
        verify(template, org.mockito.Mockito.timeout(2_000))
                .send(eq("centralized-audit.requested"), eq(event.eventId().toString()), eq(event));

        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(template.send("centralized-audit.requested", event.eventId().toString(), event)).thenReturn(failed);
        publisher.publish(event);
        verify(template, org.mockito.Mockito.timeout(2_000).times(2))
                .send(eq("centralized-audit.requested"), eq(event.eventId().toString()), eq(event));
        when(template.send("centralized-audit.requested", event.eventId().toString(), event))
                .thenThrow(new IllegalStateException("send failed"));
        publisher.publish(event);
        verify(template, org.mockito.Mockito.timeout(2_000).times(3))
                .send(eq("centralized-audit.requested"), eq(event.eventId().toString()), eq(event));
    }

    private static AuditEvent event() {
        return new AuditEvent(UUID.randomUUID(), "API-GATEWAY", Instant.parse("2026-08-11T00:00:00Z"),
                "user", "Owner", "SCHEDULER_CREATE", "SCHEDULER", null, "SUCCESS", "trace",
                "127.0.0.1", Map.of());
    }
}
