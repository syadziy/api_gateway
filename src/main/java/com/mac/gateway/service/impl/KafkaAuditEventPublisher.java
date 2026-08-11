package com.mac.gateway.service.impl;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.entities.dto.AuditEvent;
import com.mac.gateway.service.AuditEventPublisher;
import com.mac.gateway.utils.constant.GatewayLogFields;
import com.mac.gateway.utils.logging.StructuredLog;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KafkaAuditEventPublisher implements AuditEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final GatewayProperties properties;

    public KafkaAuditEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, GatewayProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(AuditEvent event) {
        if (!properties.audit().enabled()) {
            return;
        }
        Mono.fromRunnable(() -> send(event))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void send(AuditEvent event) {
        try {
            kafkaTemplate.send(properties.audit().topic(), event.eventId().toString(), event)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            StructuredLog.info(LOG, "Gateway audit event published", logFields(event, "success"));
                        } else {
                            StructuredLog.error(LOG, "Gateway audit event could not be published",
                                    logFields(event, "failure"), failure);
                        }
                    });
        } catch (RuntimeException failure) {
            StructuredLog.error(LOG, "Gateway audit event could not be published",
                    logFields(event, "failure"), failure);
        }
    }

    private static Map<String, Object> logFields(AuditEvent event, String outcome) {
        return Map.of(
                GatewayLogFields.TRACE_ID, event.traceId(),
                GatewayLogFields.EVENT_ACTION, "publishAuditEvent",
                GatewayLogFields.EVENT_OUTCOME, outcome,
                GatewayLogFields.EVENT_DATASET, "api-gateway.audit",
                "audit.event.id", event.eventId());
    }
}
