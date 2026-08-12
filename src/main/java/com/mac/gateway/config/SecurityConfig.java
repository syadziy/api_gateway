package com.mac.gateway.config;

import com.mac.gateway.config.properties.GatewayProperties;
import com.mac.gateway.security.AudienceValidator;
import com.mac.gateway.utils.handler.ReactiveErrorWriter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.security.enabled", havingValue = "false")
    SecurityWebFilterChain localSecurity(ServerHttpSecurity http) {
        return common(http).authorizeExchange(exchange -> exchange.anyExchange().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.security.enabled", havingValue = "true", matchIfMissing = true)
    SecurityWebFilterChain productionSecurity(
            ServerHttpSecurity http,
            ReactiveErrorWriter errorWriter) {
        return common(http)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/ws/alerts").permitAll()
                        .pathMatchers("/internal/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/tenants")
                            .hasAuthority("SCOPE_tenant.view")
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/tenants/*/token-policy")
                            .hasAuthority("SCOPE_tenant.update")
                        .pathMatchers(HttpMethod.GET, "/api/v1/tenants/*/users")
                            .hasAuthority("SCOPE_user.view")
                        .pathMatchers(HttpMethod.POST, "/api/v1/tenants/*/users")
                            .hasAuthority("SCOPE_user.create")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/tenants/*/users/*/roles")
                            .hasAuthority("SCOPE_role.assign")
                        .pathMatchers(HttpMethod.GET, "/api/v1/tenants/*/roles")
                            .hasAuthority("SCOPE_role.view")
                        .pathMatchers(HttpMethod.POST, "/api/v1/tenants/*/roles")
                            .hasAuthority("SCOPE_role.create")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/tenants/*/roles/*/permissions")
                            .hasAuthority("SCOPE_role.edit")
                        .pathMatchers(HttpMethod.GET, "/api/v1/tenants/*/permissions")
                            .hasAuthority("SCOPE_permission.view")
                        .pathMatchers(HttpMethod.POST, "/api/v1/tenants/*/permissions")
                            .hasAuthority("SCOPE_permission.create")
                        .pathMatchers(HttpMethod.GET, "/api/v1/alert/recipients/**")
                            .hasAuthority("SCOPE_alert.read-recipients")
                        .pathMatchers(HttpMethod.GET, "/api/v1/alert/delivery-history/**")
                            .hasAuthority("SCOPE_alert.read-notifications")
                        .pathMatchers(HttpMethod.POST, "/api/v1/alert/recipients/**")
                            .hasAuthority("SCOPE_alert.manage-recipients")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/alert/recipients/**")
                            .hasAuthority("SCOPE_alert.manage-recipients")
                        .pathMatchers(HttpMethod.DELETE, "/api/v1/alert/recipients/**")
                            .hasAuthority("SCOPE_alert.manage-recipients")
                        .pathMatchers(HttpMethod.POST, "/api/v1/alert/**").hasAuthority("SCOPE_alert.write")
                        .pathMatchers(HttpMethod.GET, "/api/v1/audit-logs/**").hasAuthority("SCOPE_audit.read")
                        .pathMatchers(HttpMethod.GET, "/api/v1/gateway-logs/**").hasAuthority("SCOPE_audit.read")
                        .pathMatchers(HttpMethod.GET, "/api/v1/histories/**").hasAuthority("SCOPE_scheduler.read")
                        .pathMatchers(HttpMethod.GET, "/api/v1/tasks/**", "/api/v1/task-groups/**",
                                "/api/v1/schedules/**")
                            .hasAuthority("SCOPE_scheduler.read")
                        .pathMatchers(HttpMethod.POST, "/api/v1/tasks/**", "/api/v1/task-groups/**",
                                "/api/v1/schedules/**")
                            .hasAuthority("SCOPE_scheduler.manage")
                        .anyExchange().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> errorWriter.write(
                                exchange, HttpStatus.UNAUTHORIZED, "GATEWAY_UNAUTHORIZED",
                                "Authentication is required"))
                        .accessDeniedHandler((exchange, exception) -> errorWriter.write(
                                exchange, HttpStatus.FORBIDDEN, "GATEWAY_FORBIDDEN", "Access is denied")))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((exchange, exception) -> errorWriter.write(
                                exchange, HttpStatus.UNAUTHORIZED, "GATEWAY_UNAUTHORIZED",
                                "Authentication is required")))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.security.enabled", havingValue = "true", matchIfMissing = true)
    ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        String issuer = properties.security().issuerUri();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(properties.security().audience())));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.security().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type",
                properties.http().correlationHeader(), properties.http().clientIdHeader(), "traceparent", "tracestate"));
        cors.setExposedHeaders(List.of(properties.http().correlationHeader()));
        cors.setAllowCredentials(properties.security().allowCredentials());
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private ServerHttpSecurity common(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(Customizer.withDefaults());
    }
}
