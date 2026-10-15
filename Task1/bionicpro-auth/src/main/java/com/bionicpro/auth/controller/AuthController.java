package com.bionicpro.auth.controller;

import static com.bionicpro.auth.security.SessionCookies.cookie;
import static com.bionicpro.auth.security.SessionCookies.LOGIN_COOKIE;
import static com.bionicpro.auth.security.SessionCookies.SESSION_COOKIE;

import com.bionicpro.auth.exception.AuthException;
import com.bionicpro.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok()
                .headers(headers())
                .body(Map.of("status", "ok"));
    }

    @GetMapping("/auth/providers")
    public ResponseEntity<?> providers() {
        return ResponseEntity.ok()
                .headers(headers())
                .body(Map.of("yandex", auth.isYandexEnabled()));
    }

    @GetMapping("/auth/login")
    public ResponseEntity<?> login(HttpServletRequest request, @RequestParam(defaultValue = "") String provider) {
        AuthService.Authorization login = auth.begin(cookie(request, LOGIN_COOKIE), provider);
        HttpHeaders headers = headers();
        headers.set(HttpHeaders.LOCATION, login.url());
        headers.add(HttpHeaders.SET_COOKIE, cookie(LOGIN_COOKIE, login.state(), 300));
        return ResponseEntity.status(302)
                .headers(headers)
                .build();
    }

    @GetMapping("/auth/callback")
    public ResponseEntity<?> callback(
            HttpServletRequest request,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String code,
            @RequestParam(defaultValue = "") String error) {
        HttpHeaders headers = headers();
        headers.add(HttpHeaders.SET_COOKIE, cookie(LOGIN_COOKIE, "", 0));
        try {
            AuthService.Ticket ticket = auth.callback(
                    state,
                    cookie(request, LOGIN_COOKIE),
                    code,
                    error,
                    cookie(request, SESSION_COOKIE));
            headers.set(HttpHeaders.LOCATION, "/");
            headers.add(HttpHeaders.SET_COOKIE, cookie(SESSION_COOKIE, ticket.id(), AuthService.SESSION_TTL));
        } catch (AuthException failure) {
            headers.set(HttpHeaders.LOCATION, "/?error=login_failed");
        }
        return ResponseEntity.status(302)
                .headers(headers)
                .build();
    }

    @GetMapping({"/auth/session", "/api/me"})
    public ResponseEntity<?> session(HttpServletRequest request) {
        return protectedCall(request, ticket -> auth.payload(ticket.session()));
    }

    @PostMapping("/auth/consent")
    public ResponseEntity<?> consent(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return protectedCall(request, ticket -> {
            if (!(body.get("accepted") instanceof Boolean accepted)) {
                throw new AuthException(400, "Укажите решение о согласии");
            }
            auth.consent(ticket.session(), accepted);
            return auth.payload(ticket.session());
        });
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        AuthService.Ticket ticket = ticket(request);
        HttpHeaders headers = headers();
        headers.add(HttpHeaders.SET_COOKIE, cookie(SESSION_COOKIE, "", 0));
        try {
            auth.logout(ticket);
            return ResponseEntity.noContent()
                    .headers(headers)
                    .build();
        } catch (AuthException error) {
            return ResponseEntity.status(502)
                    .headers(headers)
                    .body(Map.of("detail", "Локальная сессия завершена. Keycloak не подтвердил выход."));
        }
    }

    private ResponseEntity<?> protectedCall(
            HttpServletRequest request,
            Function<AuthService.Ticket, Object> action) {
        AuthService.Ticket ticket = ticket(request);
        HttpHeaders headers = headers();
        try {
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(action.apply(ticket));
        } catch (AuthException error) {
            return ResponseEntity.status(error.getStatus())
                    .headers(headers)
                    .body(Map.of("detail", error.getMessage()));
        }
    }

    private AuthService.Ticket ticket(HttpServletRequest request) {
        return (AuthService.Ticket) request.getAttribute(AuthService.Ticket.class.getName());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> problem(AuthException error) {
        return ResponseEntity.status(error.getStatus())
                .headers(headers())
                .body(Map.of("detail", error.getMessage()));
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

}
