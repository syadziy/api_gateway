package com.mac.gateway.service.impl;

import static com.mac.gateway.TestFixtures.properties;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mac.gateway.entities.dto.GatewayLogEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaGatewayLogEventPublisherTest {
    @Test
    void publishesAndContainsKafkaFailures() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        GatewayLogEvent event = new GatewayLogEvent(UUID.randomUUID(), Instant.now(), "trace", "owner",
                "tenant", "scheduler", "GET", "/api/v1/tasks", Map.of(), null, 200, Map.of(),
                null, 1, "127.0.0.1", false, false);
        CompletableFuture<SendResult<String, Object>> success = CompletableFuture.completedFuture(null);
        when(template.send("centralized-log.requested", event.eventId().toString(), event)).thenReturn(success);
        KafkaGatewayLogEventPublisher publisher = new KafkaGatewayLogEventPublisher(template, properties());

        publisher.publish(event);
        verify(template, org.mockito.Mockito.timeout(2_000)).send(
                "centralized-log.requested", event.eventId().toString(), event);

        when(template.send("centralized-log.requested", event.eventId().toString(), event))
                .thenThrow(new IllegalStateException("down"));
        publisher.publish(event);
        verify(template, org.mockito.Mockito.timeout(2_000).times(2)).send(
                "centralized-log.requested", event.eventId().toString(), event);
    }
}
