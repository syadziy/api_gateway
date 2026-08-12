package com.mac.gateway.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("UTC") @NotNull ZoneId timezone,
        @Valid @DefaultValue Http http,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Audit audit,
        @Valid @DefaultValue CentralizedLog centralizedLog,
        @Valid @DefaultValue RateLimit rateLimit,
        @Valid @DefaultValue Routes routes,
        @Valid @DefaultValue Canary canary) {

    public record Http(
            @DefaultValue("X-Correlation-Id") @NotBlank String correlationHeader,
            @DefaultValue("X-Client-Id") @NotBlank String clientIdHeader,
            @DefaultValue("64") @Min(16) @Max(128) int correlationIdMaxLength,
            @DefaultValue("5MB") @NotNull org.springframework.util.unit.DataSize maxRequestSize,
            @DefaultValue("2s") @NotNull Duration connectTimeout,
            @DefaultValue("10s") @NotNull Duration responseTimeout) {}

    public record Security(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("http://localhost:9005") @NotBlank String issuerUri,
            @DefaultValue("api-gateway") @NotBlank String audience,
            @DefaultValue("ACCESS_TOKEN") @NotBlank String authCookieName,
            @DefaultValue("https://app.example.com") List<String> allowedOrigins,
            @DefaultValue("true") boolean allowCredentials) {

        @AssertTrue(message = "Wildcard CORS origin is forbidden when credentials are enabled")
        public boolean isCorsValid() {
            return !allowCredentials || allowedOrigins == null || !allowedOrigins.contains("*");
        }
    }

    public record Audit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("centralized-audit.requested") @NotBlank String topic,
            @DefaultValue("API-GATEWAY") @NotBlank String sourceSystem,
            @DefaultValue("unknown-user") @NotBlank String fallbackActorId) {}

    public record CentralizedLog(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("centralized-log.requested") @NotBlank String topic,
            @DefaultValue("64KB") @NotNull org.springframework.util.unit.DataSize maxBodySize) {}

    public record RateLimit(
            @DefaultValue("20") @Positive int replenishRate,
            @DefaultValue("40") @Positive int burstCapacity,
            @DefaultValue("1") @Positive int requestedTokens) {}

    public record Routes(
            @DefaultValue("http://centralized-alert:9003") @NotNull URI alert,
            @DefaultValue("ws://centralized-alert:9003") @NotNull URI alertWebSocket,
            @DefaultValue("http://scheduler:9002") @NotNull URI scheduler,
            @DefaultValue("http://audit-log:9004") @NotNull URI audit,
            @DefaultValue("http://centralized-log:9006") @NotNull URI centralizedLog,
            @DefaultValue("http://usermanagement:9005") @NotNull URI usermanagement) {}

    public record Canary(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://centralized-alert-canary:9003") @NotNull URI alert,
            @DefaultValue("5") @Min(1) @Max(99) int weight) {}
}
