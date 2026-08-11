package com.mac.gateway;

import com.mac.gateway.config.properties.GatewayProperties;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.util.unit.DataSize;

public final class TestFixtures {

    private TestFixtures() {}

    public static GatewayProperties properties() {
        return new GatewayProperties(
                ZoneId.of("UTC"),
                new GatewayProperties.Http("X-Correlation-Id", "X-Client-Id", 64,
                        DataSize.ofMegabytes(5), Duration.ofSeconds(2), Duration.ofSeconds(10)),
                new GatewayProperties.Security(false, "https://issuer.example", "gateway",
                        List.of("https://app.example"), true),
                new GatewayProperties.Audit(true, "centralized-audit.requested", "API-GATEWAY", "anonymous"),
                new GatewayProperties.RateLimit(20, 40, 1),
                new GatewayProperties.Routes(
                        URI.create("http://alert:9003"),
                        URI.create("ws://alert:9003"),
                        URI.create("http://scheduler:9002"),
                        URI.create("http://audit:9004"),
                        URI.create("http://usermanagement:9005")),
                new GatewayProperties.Canary(false, URI.create("http://alert-canary:9003"), 5));
    }
}
