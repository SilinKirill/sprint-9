package com.bionicpro.auth.config;

import com.bionicpro.auth.exception.AuthException;
import com.bionicpro.auth.model.AuthSession;
import com.bionicpro.auth.security.SessionCookies;
import com.bionicpro.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String TICKET = AuthService.Ticket.class.getName();
    private static final Set<String> PROTECTED_PATHS =
        Set.of("/auth/session", "/auth/consent", "/auth/logout", "/api/me", "/reports");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    public SecurityFilterChain security(HttpSecurity http, AuthService auth) throws Exception {
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // The cookie filter validates a session-bound CSRF token and the exact Origin.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.GET, "/health", "/auth/providers", "/auth/login", "/auth/callback")
                    .permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/session", "/api/me", "/reports", "/reports/files/**")
                    .authenticated()
                .requestMatchers(HttpMethod.POST, "/auth/consent", "/auth/logout")
                    .authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    failure(response, 401, "Войдите в приложение"))
                .accessDeniedHandler((request, response, error) ->
                    failure(response, 403, "Недействительный запрос")))
            .addFilterBefore(new SessionFilter(auth), AuthorizationFilter.class)
            .build();
    }

    private static void failure(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.getWriter().write(JSON.writeValueAsString(Map.of("detail", detail)));
    }

    private static final class SessionFilter extends OncePerRequestFilter {
        private final AuthService auth;

        private SessionFilter(AuthService auth) {
            this.auth = auth;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !PROTECTED_PATHS.contains(request.getRequestURI())
                && !request.getRequestURI().startsWith("/reports/files/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            synchronized (auth) {
                AuthService.Ticket ticket;
                try {
                    ticket = authenticate(request);
                } catch (AuthException error) {
                    failure(response, error.getStatus(), error.getMessage());
                    return;
                }

                request.setAttribute(TICKET, ticket);
                setSecurityContext(ticket.session());
                writeRotatedCookie(request, response, ticket);
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }

        private AuthService.Ticket authenticate(HttpServletRequest request) {
            String sessionId = SessionCookies.cookie(request, SessionCookies.SESSION_COOKIE);
            boolean mutating = !request.getMethod().equals("GET");
            return auth.authenticate(sessionId, mutating,
                request.getHeader("Origin"), request.getHeader("X-CSRF-Token"));
        }

        private void setSecurityContext(AuthSession session) {
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                session.getClaims().getSubject(), null, authorities(session));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }

        private List<SimpleGrantedAuthority> authorities(AuthSession session) {
            Object realm = session.getClaims().getClaim("realm_access");
            Object roles = realm instanceof Map<?, ?> realmClaims ? realmClaims.get("roles") : null;
            if (!(roles instanceof List<?> roleNames)) {
                return List.of();
            }
            return roleNames.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        }

        private void writeRotatedCookie(HttpServletRequest request, HttpServletResponse response,
                                        AuthService.Ticket ticket) {
            boolean logout = request.getMethod().equals("POST") && request.getRequestURI().equals("/auth/logout");
            if (logout) {
                return;
            }
            // Send before MVC writes the body, including consent errors. Logout only deletes its cookie.
            long maxAge = Math.max(1, ticket.session().getExpiresAt() - auth.now());
            String cookie = SessionCookies.cookie(SessionCookies.SESSION_COOKIE, ticket.id(), maxAge);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie);
        }
    }
}
