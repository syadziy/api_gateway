package com.mac.gateway.config;

import com.mac.gateway.config.properties.GatewayProperties;
import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId applicationZone(GatewayProperties properties) {
        ZoneId zone = properties.timezone();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        return zone;
    }
}
