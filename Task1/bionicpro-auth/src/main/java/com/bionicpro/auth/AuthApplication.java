package com.bionicpro.auth;

import com.bionicpro.auth.config.AuthSettings;
import com.bionicpro.auth.service.AuthService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    @Bean
    public AuthService authService() throws Exception {
        AuthSettings settings = new AuthSettings(
                env("PUBLIC_ORIGIN", "https://localhost:8443"),
                env("KEYCLOAK_ISSUER", "https://localhost:8443/realms/reports-realm"),
                env("KEYCLOAK_INTERNAL_URL", "http://keycloak:8080"),
                env("KEYCLOAK_REALM", "reports-realm"),
                env("OIDC_CLIENT_ID", "bionicpro-auth"),
                env("OIDC_CLIENT_SECRET", ""),
                Path.of(env("TOKEN_KEY_FILE", "/run/secrets/token-encryption.key")),
                Path.of(env("PROFILE_DB", "/data/profiles.db")),
                Boolean.parseBoolean(env("YANDEX_ENABLED", "false")));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new AuthService(settings, httpClient, JsonMapper.builder().build(), Clock.systemUTC());
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
