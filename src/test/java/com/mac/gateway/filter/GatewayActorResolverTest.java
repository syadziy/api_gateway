package com.mac.gateway.filter;

import static com.mac.gateway.TestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.ObjectMapper;

class GatewayActorResolverTest {

    @Test
    void resolvesActorFromUnsignedBearerJwtWhenPrincipalIsMissing() {
        GatewayActorResolver resolver = new GatewayActorResolver(new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/tasks")
                        .header("Authorization", bearerToken("owner", "tenant-1")));

        GatewayActorResolver.Actor actor = resolver.resolve(exchange, properties()).block();

        assertThat(actor).isNotNull();
        assertThat(actor.username()).isEqualTo("owner");
        assertThat(actor.tenantId()).isEqualTo("tenant-1");
    }

    private static String bearerToken(String username, String tenantId) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("""
                {"username":"%s","tenant_id":"%s"}
                """.formatted(username, tenantId).trim());
        return "Bearer " + header + "." + payload + ".signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
