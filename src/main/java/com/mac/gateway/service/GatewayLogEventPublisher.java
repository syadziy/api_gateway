package com.mac.gateway.service;

import com.mac.gateway.entities.dto.GatewayLogEvent;

public interface GatewayLogEventPublisher { void publish(GatewayLogEvent event); }
