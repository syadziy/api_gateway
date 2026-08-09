package com.mac.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mac.gateway.TestFixtures;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApplicationConfigTest {

    private final TimeZone originalTimezone = TimeZone.getDefault();

    @AfterEach
    void restoreTimezone() {
        TimeZone.setDefault(originalTimezone);
    }

    @Test
    void configuresUtcClockAndApplicationTimezone() {
        ApplicationConfig configuration = new ApplicationConfig();

        assertThat(configuration.clock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(configuration.applicationZone(TestFixtures.properties())).isEqualTo(ZoneId.of("UTC"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
