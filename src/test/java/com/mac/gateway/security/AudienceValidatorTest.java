package com.mac.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("api-gateway");

    @Test
    void acceptsRequiredAudienceAndRejectsMissingAudience() {
        assertThat(validator.validate(jwt(List.of("api-gateway"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(List.of("another-api"))).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        return new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("alg", "RS256"), Map.of("sub", "user", "aud", audience));
    }
}
