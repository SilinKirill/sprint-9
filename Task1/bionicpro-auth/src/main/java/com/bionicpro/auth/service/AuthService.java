package com.bionicpro.auth.service;

import static com.bionicpro.auth.client.IdentityProviderClient.claim;
import static com.bionicpro.auth.client.IdentityProviderClient.text;

import com.bionicpro.auth.client.IdentityProviderClient;
import com.bionicpro.auth.config.AuthSettings;
import com.bionicpro.auth.exception.AuthException;
import com.bionicpro.auth.model.AuthSession;
import com.bionicpro.auth.repository.ProfileRepository;
import com.bionicpro.auth.security.TokenCipher;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

public final class AuthService {
    public static final long SESSION_TTL = 1800;
    private static final long LOGIN_TTL = 300;
    private static final long REFRESH_MARGIN = 10;
    private static final int MAX_SESSIONS = 1000;

    private final AuthSettings config;
    private final Map<String, Login> logins = new HashMap<>();
    private final Map<String, AuthSession> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final TokenCipher tokenCipher;
    private final IdentityProviderClient identityProvider;
    private final ProfileRepository profiles;

    public AuthService(AuthSettings config, HttpClient http, JsonMapper json, Clock clock) throws Exception {
        this(config, http, json, clock, "https://login.yandex.ru/info?format=json");
    }

    public AuthService(AuthSettings config, HttpClient http, JsonMapper json, Clock clock, String yandexInfoUrl) throws Exception {
        if (!config.origin().startsWith("https://") || config.clientSecret().isBlank()) {
            throw new IllegalArgumentException("HTTPS PUBLIC_ORIGIN and OIDC_CLIENT_SECRET are required");
        }
        this.config = config;
        this.clock = clock;
        tokenCipher = new TokenCipher(config.keyFile());
        profiles = new ProfileRepository(config.profileDb(), json);
        identityProvider = new IdentityProviderClient(config, http, json, clock, yandexInfoUrl);
    }

    private record Login(String verifier, String nonce, long expires) {}
    public record Authorization(String state, String url) {}
    public record Ticket(String id, AuthSession session) {}

    public synchronized Authorization begin(String previousLogin, String provider) {
        if (!provider.isEmpty() && (!provider.equals("yandex") || !config.yandexEnabled())) {
            throw new AuthException(400, "Способ входа не настроен");
        }
        removeExpiredSessions();
        if (logins.size() >= MAX_SESSIONS || sessions.size() >= MAX_SESSIONS) {
            throw new AuthException(503, "Повторите вход позже");
        }
        logins.remove(previousLogin);
        String state = randomId();
        String nonce = randomId();
        String verifier = randomId();
        logins.put(state, new Login(verifier, nonce, now() + LOGIN_TTL));

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", config.clientId());
        parameters.put("redirect_uri", config.origin() + "/auth/callback");
        parameters.put("response_type", "code");
        parameters.put("scope", "openid");
        parameters.put("state", state);
        parameters.put("nonce", nonce);
        parameters.put("code_challenge", pkceChallenge(verifier));
        parameters.put("code_challenge_method", "S256");
        if (!provider.isEmpty()) {
            parameters.put("kc_idp_hint", provider);
        }
        String url = config.issuer() + "/protocol/openid-connect/auth?" + IdentityProviderClient.form(parameters);
        return new Authorization(state, url);
    }

    public synchronized Ticket callback(String state, String loginCookie, String code, String error, String oldSession) {
        Login login = consumeLogin(state, loginCookie, code, error);
        Map<String, Object> tokens = identityProvider.exchangeCode(code, login.verifier());
        Jwt identity = identityProvider.validateIdToken(text(tokens, "id_token"), login.nonce());
        Jwt access = identityProvider.validateAccessToken(text(tokens, "access_token"));
        if (!identity.getSubject().equals(access.getSubject())) {
            throw new AuthException(401, "Недействительная сессия");
        }
        AuthSession session = new AuthSession(
            text(tokens, "access_token"), tokenCipher.encrypt(text(tokens, "refresh_token")), access,
            login.nonce(), randomId(), now() + SESSION_TTL,
            claim(identity, "identity_provider"), claim(identity, "yandex_subject"));
        sessions.remove(oldSession);
        return registerSession(session);
    }

    private Login consumeLogin(String state, String loginCookie, String code, String error) {
        if (state.isEmpty() || !same(state, loginCookie)) {
            throw new AuthException(401, "Недействительный вход");
        }
        Login login = logins.remove(state);
        if (login == null || login.expires() <= now() || !error.isEmpty() || code.isEmpty()) {
            throw new AuthException(401, "Недействительный вход");
        }
        return login;
    }

    public synchronized Ticket authenticate(String sessionId, boolean mutating, String origin, String csrf) {
        AuthSession session = sessions.get(sessionId);
        if (session == null || session.getExpiresAt() <= now()) {
            sessions.remove(sessionId);
            throw new AuthException(401, "Войдите в приложение");
        }
        if (mutating && (!config.origin().equals(origin) || !same(csrf, session.getCsrfToken()))) {
            throw new AuthException(403, "Недействительный запрос");
        }
        // Consume before network I/O: concurrent requests cannot reuse the old identifier.
        sessions.remove(sessionId);
        if (session.getClaims().getExpiresAt().getEpochSecond() <= now() + REFRESH_MARGIN) {
            refresh(session);
        } else {
            session.setClaims(identityProvider.validateAccessToken(session.getAccessToken()));
        }
        return registerSession(session);
    }

    private void refresh(AuthSession session) {
        String previousToken = tokenCipher.decrypt(session.getEncryptedRefreshToken());
        Map<String, Object> tokens = identityProvider.refresh(previousToken);
        Jwt access = identityProvider.validateAccessToken(text(tokens, "access_token"));
        String nextRefreshToken = text(tokens, "refresh_token");
        if (!session.getClaims().getSubject().equals(access.getSubject()) || same(previousToken, nextRefreshToken)) {
            throw new AuthException(401, "Сессия завершена");
        }
        if (tokens.containsKey("id_token")) {
            validateRefreshedIdentity(text(tokens, "id_token"), access.getSubject(), session.getNonce());
        }
        session.setAccessToken(text(tokens, "access_token"));
        session.setClaims(access);
        session.setEncryptedRefreshToken(tokenCipher.encrypt(nextRefreshToken));
    }

    private void validateRefreshedIdentity(String idToken, String subject, String nonce) {
        Jwt identity = identityProvider.validateIdToken(idToken, null);
        boolean sameSubject = identity.getSubject().equals(subject);
        boolean sameNonce = !identity.hasClaim("nonce") || same(claim(identity, "nonce"), nonce);
        if (!sameSubject || !sameNonce) {
            throw new AuthException(401, "Сессия завершена");
        }
    }

    public Map<String, Object> payload(AuthSession session) {
        Map<String, Object> result = profiles.read(session.getClaims().getSubject());
        Object realm = session.getClaims().getClaim("realm_access");
        Object roles = realm instanceof Map<?, ?> realmClaims ? realmClaims.get("roles") : List.of();
        result.put("user", Map.of(
            "id", session.getClaims().getSubject(),
            "username", claim(session.getClaims(), "preferred_username"),
            "roles", roles == null ? List.of() : roles));
        result.put("csrfToken", session.getCsrfToken());
        result.put("yandexLogin", session.getProvider().equals("yandex"));
        return result;
    }

    public void consent(AuthSession session, boolean accepted) {
        if (!session.getProvider().equals("yandex")) {
            throw new AuthException(400, "Согласие доступно после входа через Яндекс");
        }
        Map<String, Object> profile = null;
        if (accepted) {
            profile = readYandexProfile(session);
        }
        profiles.save(session.getClaims().getSubject(), accepted, profile, now());
    }

    private Map<String, Object> readYandexProfile(AuthSession session) {
        Map<String, Object> source = identityProvider.yandexProfile(session.getAccessToken());
        if (session.getExternalId().isEmpty() || !session.getExternalId().equals(String.valueOf(source.get("id")))) {
            throw new AuthException(502, "Не удалось проверить профиль Яндекса");
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        for (String field : List.of("id", "login", "display_name", "default_email")) {
            if (source.get(field) instanceof String value) {
                profile.put(field, value);
            }
        }
        return profile;
    }

    public void logout(Ticket ticket) {
        synchronized (this) {
            sessions.remove(ticket.id());
        }
        identityProvider.logout(tokenCipher.decrypt(ticket.session().getEncryptedRefreshToken()));
    }

    private Ticket registerSession(AuthSession session) {
        String sessionId = randomId();
        sessions.put(sessionId, session);
        return new Ticket(sessionId, session);
    }

    private void removeExpiredSessions() {
        logins.entrySet().removeIf(entry -> entry.getValue().expires() <= now());
        sessions.entrySet().removeIf(entry -> entry.getValue().getExpiresAt() <= now());
    }

    public boolean isYandexEnabled() {
        return config.yandexEnabled();
    }

    public long now() {
        return clock.instant().getEpochSecond();
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String pkceChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }
}
