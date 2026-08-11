package com.mac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "gateway.security.enabled=false",
        "gateway.audit.enabled=false",
        "management.server.port=0",
        "gateway.canary.enabled=true",
        "gateway.canary.alert=http://alert-canary.test:9003",
        "gateway.canary.weight=5",
        "gateway.routes.alert=http://alert.test:9003",
        "gateway.routes.alert-web-socket=ws://alert.test:9003",
        "gateway.routes.scheduler=http://scheduler.test:9002",
        "gateway.routes.audit=http://audit.test:9004",
        "gateway.routes.usermanagement=http://usermanagement.test:9005"
})
class GatewayContextIntegrationTest {

    @Autowired
    private RouteLocator routeLocator;

    @MockitoBean
    private RedisRateLimiter rateLimiter;

    @Test
    void startsReactiveContextWithExpectedPlatformVipRoutes() {
        Map<String, String> routes = routeLocator.getRoutes().collectList().block().stream()
                .collect(Collectors.toMap(route -> route.getId(), route -> route.getUri().toString()));

        assertThat(routes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "centralized-alert-stable", "http://alert.test:9003",
                "centralized-alert-canary", "http://alert-canary.test:9003",
                "centralized-alert-websocket", "ws://alert.test:9003",
                "scheduler", "http://scheduler.test:9002",
                "audit-log", "http://audit.test:9004",
                "usermanagement", "http://usermanagement.test:9005"));
    }
}
