package com.mac.gateway.utils.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BodySanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recursivelyRedactsSensitiveFieldsAndRejectsInvalidJson() {
        var sanitized = BodySanitizer.sanitize(mapper, """
                {"username":"owner","password":"secret","nested":{"accessToken":"token"},
                 "items":[{"api_key":"key"}]}
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(sanitized.get("username").asText()).isEqualTo("owner");
        assertThat(sanitized.get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("nested").get("accessToken").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("items").get(0).get("api_key").asText()).isEqualTo("[REDACTED]");
        assertThat(BodySanitizer.sanitize(mapper, "not-json".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(BodySanitizer.sanitize(mapper, new byte[0])).isNull();
    }
}
