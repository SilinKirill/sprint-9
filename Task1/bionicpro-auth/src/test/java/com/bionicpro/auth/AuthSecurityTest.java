package com.bionicpro.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bionicpro.auth.config.AuthSettings;
import com.bionicpro.auth.config.SecurityConfig;
import com.bionicpro.auth.controller.AuthController;
import com.bionicpro.auth.exception.AuthException;
import com.bionicpro.auth.model.AuthSession;
import com.bionicpro.auth.security.SessionCookies;
import com.bionicpro.auth.security.TokenCipher;
import com.bionicpro.auth.service.AuthService;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

public class AuthSecurityTest {
    private static final String ORIGIN = "https://localhost:8443";
    private static final String ISSUER = ORIGIN + "/realms/reports-realm";

    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock();

    private HttpServer upstream;
    private RSAKey signingKey;
    private AuthService auth;
    private TokenCipher tokenCipher;
    private MockMvc mvc;
    private AnnotationConfigWebApplicationContext context;

    private String nonce = "";
    private String tokenCorruption = "";
    private String profileId = "external-42";
    private int tokenLifetime = 120;
    private int refreshCount;
    private int externalCallCount;
    private boolean refreshFails;
    private boolean logoutFails;
    private boolean yandexLogin;
    private Map<String, String> tokenForm;

    @BeforeEach
    void setup() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test").generate();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", this::handle);
        upstream.start();

        String upstreamUrl = "http://127.0.0.1:" + upstream.getAddress().getPort();
        Path keyFile = directory.resolve("key");
        Files.writeString(keyFile, Base64.getUrlEncoder().encodeToString(new byte[32]));
        tokenCipher = new TokenCipher(keyFile);

        AuthSettings settings = new AuthSettings(
                ORIGIN,
                ISSUER,
                upstreamUrl,
                "reports-realm",
                "bionicpro-auth",
                "test-secret",
                keyFile,
                directory.resolve("profiles.db"),
                true);
        auth = new AuthService(settings, HttpClient.newHttpClient(), json, clock, upstreamUrl + "/info");

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("authService", auth));
        context.register(TestWeb.class, AuthController.class, SecurityConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
        upstream.stop(0);
    }

    @Configuration
    @EnableWebMvc
    public static class TestWeb {}

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            Object responseBody;
            int status = 200;

            if (path.endsWith("/certs")) {
                responseBody = Map.of("keys", List.of(signingKey.toPublicJWK().toJSONObject()));
            } else if (path.endsWith("/broker/yandex/token")) {
                externalCallCount++;
                responseBody = Map.of("access_token", "external-secret");
            } else if (path.endsWith("/token")) {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                tokenForm = query(requestBody);
                if ("refresh_token".equals(tokenForm.get("grant_type"))) {
                    refreshCount++;
                    assertEquals("refresh-" + (refreshCount - 1), tokenForm.get("refresh_token"));
                    tokenLifetime = 120;
                }
                assertEquals("test-secret", tokenForm.get("client_secret"));

                if (refreshFails && refreshCount > 0) {
                    status = 400;
                    responseBody = Map.of("error", "invalid_grant");
                } else {
                    responseBody = Map.of(
                            "token_type", "Bearer",
                            "access_token", token(false),
                            "id_token", token(true),
                            "refresh_token", "refresh-" + refreshCount);
                }
            } else if (path.endsWith("/logout")) {
                status = logoutFails ? 503 : 200;
                responseBody = Map.of();
            } else if (path.equals("/info")) {
                externalCallCount++;
                assertEquals("OAuth external-secret", exchange.getRequestHeaders().getFirst("Authorization"));
                responseBody = Map.of(
                        "id", profileId,
                        "login", "test",
                        "default_email", "user@example.org",
                        "birthday", "2000-01-01");
            } else {
                status = 404;
                responseBody = Map.of();
            }

            byte[] responseBytes = json.writeValueAsBytes(responseBody);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            exchange.close();
        }
    }

    private String token(boolean idToken) throws Exception {
        long issuedAt = clock.instant().getEpochSecond();
        int lifetime = tokenLifetime;
        if (!idToken && tokenCorruption.equals("expired")) {
            lifetime = -1;
        } else if (!idToken && tokenCorruption.equals("long")) {
            lifetime = 121;
        }

        String issuer = !idToken && tokenCorruption.equals("issuer") ? "https://wrong" : ISSUER;
        String audience = !idToken && tokenCorruption.equals("audience") ? "wrong" : "bionicpro-auth";
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("user-1")
                .issueTime(Date.from(Instant.ofEpochSecond(issuedAt)))
                .expirationTime(Date.from(Instant.ofEpochSecond(issuedAt + lifetime)))
                .claim("typ", idToken ? "ID" : "Bearer")
                .claim("azp", "bionicpro-auth")
                .claim("preferred_username", "test-user")
                .claim("realm_access", Map.of("roles", List.of("user")));

        if (idToken) {
            claims.claim("nonce", tokenCorruption.equals("nonce") ? "wrong" : nonce);
            if (yandexLogin) {
                claims.claim("identity_provider", "yandex")
                        .claim("identity_provider_identity", "yandex_external-42")
                        .claim("yandex_subject", "external-42");
            }
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("test")
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT signedToken = new SignedJWT(header, claims.build());
        RSAKey key = tokenCorruption.equals("signature")
                ? new RSAKeyGenerator(2048).generate()
                : signingKey;
        signedToken.sign(new RSASSASigner(key));
        return signedToken.serialize();
    }

    private Map<String, String> begin() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/auth/login"))
                .andReturn()
                .getResponse();
        Map<String, String> parameters = query(URI.create(response.getHeader("Location")).getRawQuery());
        nonce = parameters.get("nonce");
        return parameters;
    }

    private MockHttpServletResponse callback(Map<String, String> parameters, String stateCookie) throws Exception {
        return mvc.perform(get("/auth/callback")
                        .param("state", parameters.get("state"))
                        .param("code", "code")
                        .cookie(new Cookie(SessionCookies.LOGIN_COOKIE, stateCookie)))
                .andReturn()
                .getResponse();
    }

    private String login() throws Exception {
        Map<String, String> parameters = begin();
        MockHttpServletResponse response = callback(parameters, parameters.get("state"));
        assertEquals("/", response.getHeader("Location"));
        return sessionId(response);
    }

    private String sessionId(MockHttpServletResponse response) {
        return response.getCookie(SessionCookies.SESSION_COOKIE).getValue();
    }

    private static Map<String, String> query(String value) {
        Map<String, String> parameters = new HashMap<String, String>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String parameterValue = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            parameters.put(name, parameterValue);
        }
        return parameters;
    }

    private MockHttpServletResponse getSession(String sessionId) throws Exception {
        return mvc.perform(get("/auth/session")
                        .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId)))
                .andReturn()
                .getResponse();
    }

    @Test
    void pkceCookiesAndTokenIsolation() throws Exception {
        Map<String, String> parameters = begin();
        MockHttpServletResponse response = callback(parameters, parameters.get("state"));
        byte[] verifierHash = MessageDigest.getInstance("SHA-256")
                .digest(tokenForm.get("code_verifier").getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierHash);
        assertEquals(challenge, parameters.get("code_challenge"));
        assertEquals("S256", parameters.get("code_challenge_method"));

        String cookies = String.join(";", response.getHeaders("Set-Cookie"));
        for (String flag : List.of("Secure", "HttpOnly", "SameSite=Lax", "Path=/")) {
            assertTrue(cookies.contains(flag));
        }
        assertFalse(cookies.contains("Domain="));

        AuthSession session = sessions().get(sessionId(response));
        assertEquals("refresh-0", tokenCipher.decrypt(session.getEncryptedRefreshToken()));
        assertFalse(new String(session.getEncryptedRefreshToken(), StandardCharsets.UTF_8).contains("refresh-0"));

        String responseBody = getSession(sessionId(response)).getContentAsString();
        assertFalse(responseBody.contains(session.getAccessToken()));
        assertFalse(responseBody.contains("refresh-0"));

        session.getEncryptedRefreshToken()[15] ^= 1;
        assertThrows(AuthException.class, () -> tokenCipher.decrypt(session.getEncryptedRefreshToken()));
    }

    @Test
    void stateIsBrowserBoundSingleUseAndExpires() throws Exception {
        Map<String, String> parameters = begin();
        assertTrue(callback(parameters, "wrong").getHeader("Location").contains("error="));
        assertEquals("/", callback(parameters, parameters.get("state")).getHeader("Location"));
        assertTrue(callback(parameters, parameters.get("state")).getHeader("Location").contains("error="));

        Map<String, String> expiredParameters = begin();
        clock.advance(301);
        assertTrue(callback(expiredParameters, expiredParameters.get("state"))
                .getHeader("Location").contains("error="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonce", "issuer", "audience", "signature", "expired", "long"})
    void untrustedJwtCannotCreateSession(String reason) throws Exception {
        tokenCorruption = reason;
        Map<String, String> parameters = begin();
        assertTrue(callback(parameters, parameters.get("state")).getHeader("Location").contains("error="));
        assertTrue(sessions().isEmpty());
    }

    @Test
    void everyRequestRotatesAndConcurrentReplayHasOneWinner() throws Exception {
        String initialSessionId = login();
        String nextSessionId = sessionId(getSession(initialSessionId));
        assertNotEquals(initialSessionId, nextSessionId);
        assertEquals(401, getSession(initialSessionId).getStatus());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> request = () -> getSession(nextSessionId).getStatus();
            List<Future<Integer>> results = executor.invokeAll(List.of(request, request));
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(List.of(200, 401), statuses.stream().sorted().toList());
        }
    }

    @Test
    void refreshRotatesTokensAndFailureClosesSession() throws Exception {
        tokenLifetime = 5;
        String sessionId = login();
        MockHttpServletResponse response = getSession(sessionId);
        assertEquals(200, response.getStatus());
        assertEquals(1, refreshCount);

        AuthSession session = sessions().get(sessionId(response));
        assertEquals("refresh-1", tokenCipher.decrypt(session.getEncryptedRefreshToken()));

        clock.advance(121);
        refreshFails = true;
        assertEquals(502, getSession(sessionId(response)).getStatus());
        assertTrue(sessions().isEmpty());
    }

    @Test
    void csrfAndOriginRequiredWithoutConsumingValidSession() throws Exception {
        String sessionId = login();
        String csrfToken = sessions().get(sessionId).getCsrfToken();

        MockHttpServletResponse missingCsrf = mvc.perform(post("/auth/logout")
                        .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId))
                        .header("Origin", ORIGIN))
                .andReturn()
                .getResponse();
        assertEquals(403, missingCsrf.getStatus());

        MockHttpServletResponse wrongOrigin = mvc.perform(post("/auth/logout")
                        .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId))
                        .header("Origin", "https://wrong")
                        .header("X-CSRF-Token", csrfToken))
                .andReturn()
                .getResponse();
        assertEquals(403, wrongOrigin.getStatus());
        assertTrue(sessions().containsKey(sessionId));
    }

    @Test
    void profileFetchedOnlyWithConsentAndBoundToVerifiedIdentity() throws Exception {
        yandexLogin = true;
        String sessionId = login();
        AuthSession session = sessions().get(sessionId);
        assertEquals("pending", auth.payload(session).get("profileConsent"));
        assertEquals(0, externalCallCount);

        auth.consent(session, false);
        assertEquals(0, externalCallCount);

        profileId = "different-user";
        assertThrows(AuthException.class, () -> auth.consent(session, true));
        assertEquals("declined", auth.payload(session).get("profileConsent"));

        profileId = "external-42";
        auth.consent(session, true);
        String savedProfile = json.writeValueAsString(auth.payload(session));
        assertTrue(savedProfile.contains("user@example.org"));
        assertFalse(savedProfile.contains("birthday"));
        assertFalse(savedProfile.contains("external-secret"));

        auth.consent(session, false);
        assertNull(auth.payload(session).get("profile"));
    }

    @Test
    void yandexConsentAndProfileSurviveTokenRefresh() throws Exception {
        yandexLogin = true;
        String sessionId = login();
        MockHttpServletResponse consent = mvc.perform(post("/auth/consent")
                        .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", sessions().get(sessionId).getCsrfToken())
                        .contentType("application/json")
                        .content("{\"accepted\":true}"))
                .andReturn()
                .getResponse();
        assertEquals(200, consent.getStatus());

        clock.advance(130);
        MockHttpServletResponse refreshed = getSession(sessionId(consent));
        assertEquals(200, refreshed.getStatus());
        assertEquals(1, refreshCount);
        assertNotEquals(sessionId(consent), sessionId(refreshed));
        Map<?, ?> payload = json.readValue(refreshed.getContentAsString(), Map.class);
        assertEquals(true, payload.get("yandexLogin"));
        assertEquals("accepted", payload.get("profileConsent"));
        assertEquals("external-42", ((Map<?, ?>) payload.get("profile")).get("id"));
        assertEquals(2, externalCallCount); // Refresh does not fetch the profile again.
    }

    @Test
    void logoutInvalidatesSessionEvenIfKeycloakFails() throws Exception {
        for (boolean fails : List.of(false, true)) {
            String sessionId = login();
            logoutFails = fails;
            MockHttpServletResponse response = mvc.perform(post("/auth/logout")
                            .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId))
                            .header("Origin", ORIGIN)
                            .header("X-CSRF-Token", sessions().get(sessionId).getCsrfToken()))
                    .andReturn()
                    .getResponse();
            assertEquals(fails ? 502 : 204, response.getStatus());
            assertTrue(sessions().isEmpty());
            assertEquals(0, response.getCookie(SessionCookies.SESSION_COOKIE).getMaxAge());
        }
    }

    @Test
    void providersAreExplicitAndConsentRequiresBoolean() throws Exception {
        assertEquals(401, mvc.perform(get("/api/me")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/unknown")).andReturn().getResponse().getStatus());
        assertFalse(getSession("invalid").getHeaders("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.contains("JSESSIONID")));

        MockHttpServletResponse invalidProvider = mvc.perform(get("/auth/login").param("provider", "arbitrary"))
                .andReturn()
                .getResponse();
        assertEquals(400, invalidProvider.getStatus());

        String sessionId = login();
        MockHttpServletResponse invalidConsent = mvc.perform(post("/auth/consent")
                        .cookie(new Cookie(SessionCookies.SESSION_COOKIE, sessionId))
                        .header("Origin", ORIGIN)
                        .header("X-CSRF-Token", sessions().get(sessionId).getCsrfToken())
                        .contentType("application/json")
                        .content("{\"accepted\":\"true\"}"))
                .andReturn()
                .getResponse();
        assertEquals(400, invalidConsent.getStatus());
    }

    @Test
    void expiredSessionCannotBeRefreshed() throws Exception {
        String sessionId = login();
        clock.advance(1801);
        assertEquals(401, getSession(sessionId).getStatus());
        assertTrue(sessions().isEmpty());
        assertEquals(0, refreshCount);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AuthSession> sessions() {
        return (Map<String, AuthSession>) ReflectionTestUtils.getField(auth, "sessions");
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.now();

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
