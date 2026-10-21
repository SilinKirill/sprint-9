package com.bionicpro.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

public final class SessionCookies {
    public static final String SESSION_COOKIE = "__Host-bionicpro_session";
    public static final String LOGIN_COOKIE = "__Host-bionicpro_login";

    private SessionCookies() {
    }

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals(name)) {
                    return cookie.getValue();
                }
            }
        }
        return "";
    }

    public static String cookie(String name, String value, long age) {
        return ResponseCookie.from(name, value)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(age)
                .build()
                .toString();
    }
}
