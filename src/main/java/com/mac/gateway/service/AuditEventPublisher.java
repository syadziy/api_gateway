package com.mac.gateway.service;

import com.mac.gateway.entities.dto.AuditEvent;

public interface AuditEventPublisher {

    void publish(AuditEvent event);
}
