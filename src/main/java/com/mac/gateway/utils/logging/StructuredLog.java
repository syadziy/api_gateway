package com.mac.gateway.utils.logging;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

public final class StructuredLog {

    private StructuredLog() {}

    public static void info(Logger logger, String message, Map<String, Object> fields) {
        emit(logger.atInfo(), message, fields, null);
    }

    public static void error(Logger logger, String message, Map<String, Object> fields, Throwable error) {
        emit(logger.atError(), message, fields, error);
    }

    private static void emit(
            LoggingEventBuilder builder,
            String message,
            Map<String, Object> fields,
            Throwable error) {
        if (error != null) {
            builder.setCause(error);
        }
        fields.forEach(builder::addKeyValue);
        builder.log(message);
    }
}
