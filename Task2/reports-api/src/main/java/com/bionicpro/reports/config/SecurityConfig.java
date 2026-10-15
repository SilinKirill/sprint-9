package com.bionicpro.reports.config;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${KEYCLOAK_JWKS_URL:http://keycloak:8080/realms/reports-realm/protocol/openid-connect/certs}") String jwks,
            @Value("${KEYCLOAK_ISSUER:https://localhost:8443/realms/reports-realm}") String issuer,
            @Value("${OIDC_AUDIENCE:bionicpro-auth}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
            .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            token -> {
                Instant now = Instant.now();
                boolean valid = token.getAudience().contains(audience)
                    && audience.equals(token.getClaimAsString("azp"))
                    && "Bearer".equals(token.getClaimAsString("typ"))
                    && token.getSubject() != null && !token.getSubject().isBlank()
                    && token.getExpiresAt() != null && token.getExpiresAt().isAfter(now)
                    && token.getIssuedAt() != null && !token.getIssuedAt().isAfter(now.plusSeconds(5));
                return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
        return decoder;
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/reports").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(server -> server.jwt(jwt -> {}))
            .headers(headers -> headers.cacheControl(cache -> {}))
            .build();
    }
}
