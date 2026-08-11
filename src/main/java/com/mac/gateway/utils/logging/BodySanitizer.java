package com.mac.gateway.utils.logging;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class BodySanitizer {
    private static final Set<String> SENSITIVE_PARTS = Set.of(
            "password", "passwd", "token", "secret", "authorization", "cookie", "credential",
            "apikey", "api_key", "cardnumber", "cvv");
    private BodySanitizer() {}

    public static JsonNode sanitize(ObjectMapper mapper, byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode root = mapper.readTree(body);
            redact(root);
            return root;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (var entry : object.properties()) {
                if (isSensitive(entry.getKey())) object.put(entry.getKey(), "[REDACTED]");
                else redact(entry.getValue());
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(BodySanitizer::redact);
        }
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE_PARTS.stream().anyMatch(part -> normalized.contains(part.replace("_", "")));
    }
}
