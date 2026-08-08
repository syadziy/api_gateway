package com.mac.gateway.utils.logging;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StructuredLogTest {

    @Test
    void emitsInfoAndErrorFields() {
        var logger = LoggerFactory.getLogger(StructuredLogTest.class);
        assertThatNoException().isThrownBy(() -> {
            StructuredLog.info(logger, "info", Map.of("event.action", "test"));
            StructuredLog.error(logger, "error", Map.of("event.outcome", "failure"),
                    new IllegalStateException("failure"));
        });
    }
}
