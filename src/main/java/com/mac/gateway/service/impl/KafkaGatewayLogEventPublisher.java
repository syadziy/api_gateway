package com.mac.gateway.service.impl;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.entities.dto.GatewayLogEvent;
import com.mac.gateway.service.GatewayLogEventPublisher;
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
public class KafkaGatewayLogEventPublisher implements GatewayLogEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaGatewayLogEventPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final GatewayProperties properties;

    public KafkaGatewayLogEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, GatewayProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(GatewayLogEvent event) {
        if (!properties.centralizedLog().enabled()) return;
        Mono.fromRunnable(() -> send(event)).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void send(GatewayLogEvent event) {
        try {
            kafkaTemplate.send(properties.centralizedLog().topic(), event.eventId().toString(), event)
                    .whenComplete((result, failure) -> {
                        if (failure != null) StructuredLog.error(LOG, "Gateway log event could not be published", fields(event, "failure"), failure);
                    });
        } catch (RuntimeException failure) {
            StructuredLog.error(LOG, "Gateway log event could not be published", fields(event, "failure"), failure);
        }
    }

    private static Map<String, Object> fields(GatewayLogEvent event, String outcome) {
        return Map.of(GatewayLogFields.TRACE_ID, event.traceId(), GatewayLogFields.EVENT_ACTION,
                "publishGatewayLogEvent", GatewayLogFields.EVENT_OUTCOME, outcome,
                GatewayLogFields.EVENT_DATASET, "api-gateway.centralized-log", "event.id", event.eventId());
    }
}
