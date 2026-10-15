package com.bionicpro.auth.client;

import com.bionicpro.auth.config.AuthSettings;
import com.bionicpro.auth.exception.AuthException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class IdentityProviderClient {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityProviderClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final AuthSettings config;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final NimbusJwtDecoder decoder;
    private final String yandexInfoUrl;

    public IdentityProviderClient(AuthSettings config, HttpClient http, JsonMapper json, Clock clock, String yandexInfoUrl) {
        this.config = config;
        this.http = http;
        this.json = json;
        this.clock = clock;
        this.yandexInfoUrl = yandexInfoUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(REQUEST_TIMEOUT);
        requestFactory.setReadTimeout(REQUEST_TIMEOUT);
        decoder = NimbusJwtDecoder.withJwkSetUri(config.oidc() + "/certs")
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(requestFactory))
            .build();
        // Nimbus verifies signatures; the checks below apply strict claims and expiry rules.
        decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
    }

    public Map<String, Object> exchangeCode(String code, String verifier) {
        return tokens(Map.of(
            "grant_type", "authorization_code", "code", code,
            "redirect_uri", config.origin() + "/auth/callback", "code_verifier", verifier));
    }

    public Map<String, Object> refresh(String refreshToken) {
        return tokens(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    public void logout(String refreshToken) {
        request("POST", config.oidc() + "/logout", Map.of(
            "client_id", config.clientId(), "client_secret", config.clientSecret(),
            "refresh_token", refreshToken), null);
    }

    public Map<String, Object> yandexProfile(String accessToken) {
        String brokerUrl = config.internalUrl() + "/realms/" + config.realm() + "/broker/yandex/token";
        Map<String, Object> brokerResponse = parse(request("GET", brokerUrl, null, "Bearer " + accessToken));
        String externalToken = text(brokerResponse, "access_token");
        if (externalToken.isEmpty()) {
            throw new AuthException(502, "Не удалось получить доступ к профилю Яндекса");
        }
        return parse(request("GET", yandexInfoUrl, null, "OAuth " + externalToken));
    }

    public Jwt validateAccessToken(String token) {
        return decode(token, "Bearer", null);
    }

    public Jwt validateIdToken(String token, String nonce) {
        return decode(token, "ID", nonce);
    }

    private Jwt decode(String token, String expectedType, String nonce) {
        try {
            Jwt jwt = decoder.decode(token);
            validateIdentityClaims(jwt, expectedType);
            validateTimestamps(jwt, expectedType);
            if (nonce != null && !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                    claim(jwt, "nonce").getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Invalid nonce");
            }
            return jwt;
        } catch (RuntimeException error) {
            throw new AuthException(401, "Недействительный токен");
        }
    }

    private void validateIdentityClaims(Jwt token, String expectedType) {
        if (!config.issuer().equals(claim(token, "iss"))) {
            throw new IllegalArgumentException("Invalid issuer");
        }
        if (token.getAudience() == null || !token.getAudience().contains(config.clientId())) {
            throw new IllegalArgumentException("Invalid audience");
        }
        if (token.getSubject() == null || token.getSubject().isBlank()) {
            throw new IllegalArgumentException("Missing subject");
        }
        if (!expectedType.equals(claim(token, "typ"))) {
            throw new IllegalArgumentException("Invalid token type");
        }
        if (token.hasClaim("azp") && !config.clientId().equals(claim(token, "azp"))) {
            throw new IllegalArgumentException("Invalid authorized party");
        }
    }

    private void validateTimestamps(Jwt token, String tokenType) {
        long now = clock.instant().getEpochSecond();
        if (token.getExpiresAt() == null || token.getIssuedAt() == null) {
            throw new IllegalArgumentException("Missing timestamps");
        }
        if (token.getExpiresAt().getEpochSecond() <= now || token.getIssuedAt().getEpochSecond() > now) {
            throw new IllegalArgumentException("Invalid token lifetime");
        }
        if (token.getNotBefore() != null && token.getNotBefore().getEpochSecond() > now) {
            throw new IllegalArgumentException("Token is not yet valid");
        }
        long lifetime = Duration.between(token.getIssuedAt(), token.getExpiresAt()).getSeconds();
        if (tokenType.equals("Bearer") && lifetime > 120) {
            throw new IllegalArgumentException("Access token lifetime exceeds 120 seconds");
        }
    }

    private Map<String, Object> tokens(Map<String, String> grant) {
        Map<String, String> data = new HashMap<>(grant);
        data.put("client_id", config.clientId());
        data.put("client_secret", config.clientSecret());
        Map<String, Object> response = parse(request("POST", config.oidc() + "/token", data, null));
        boolean bearer = "bearer".equalsIgnoreCase(text(response, "token_type"));
        boolean tokensPresent = !text(response, "access_token").isEmpty() && !text(response, "refresh_token").isEmpty();
        if (!bearer || !tokensPresent) {
            throw new AuthException(401, "Недействительный ответ сервиса входа");
        }
        return response;
    }

    private String request(String method, String url, Map<String, String> data, String authorization) {
        URI endpoint = URI.create(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (data != null) {
            request.header("Content-Type", "application/x-www-form-urlencoded");
        }
        HttpRequest.BodyPublisher body = data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form(data));
        request.method(method, body);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warn("Identity provider {} {} returned HTTP {}", method, endpoint.getPath(), response.statusCode());
                throw new AuthException(502, "Сервис входа отклонил запрос");
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AuthException(502, "Сервис входа временно недоступен");
        } catch (java.io.IOException error) {
            LOG.warn("Identity provider {} {} failed: {}", method, endpoint.getPath(), error.toString());
            throw new AuthException(502, "Сервис входа временно недоступен");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        try {
            Map<String, Object> result = json.readValue(value, Map.class);
            if (result == null) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException error) {
            throw new AuthException(502, "Недействительный ответ сервиса входа");
        }
    }

    public static String form(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String text(Map<String, Object> values, String key) {
        return values.get(key) instanceof String value ? value : "";
    }

    public static String claim(Jwt token, String name) {
        Object value = token.getClaim(name);
        return value == null ? "" : value.toString();
    }
}
