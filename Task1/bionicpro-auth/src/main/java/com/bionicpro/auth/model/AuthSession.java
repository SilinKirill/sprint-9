package com.bionicpro.auth.model;

import org.springframework.security.oauth2.jwt.Jwt;

public final class AuthSession {
    private String accessToken;
    private byte[] encryptedRefreshToken;
    private Jwt claims;
    private final String nonce;
    private final String csrfToken;
    private final String provider;
    private final String externalId;
    private final long expiresAt;

    public AuthSession(String accessToken, byte[] encryptedRefreshToken, Jwt claims,
                       String nonce, String csrfToken, long expiresAt, String provider, String externalId) {
        this.accessToken = accessToken;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.claims = claims;
        this.nonce = nonce;
        this.csrfToken = csrfToken;
        this.provider = provider;
        this.externalId = externalId;
        this.expiresAt = expiresAt;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public byte[] getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public void setEncryptedRefreshToken(byte[] encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
    }

    public Jwt getClaims() {
        return claims;
    }

    public void setClaims(Jwt claims) {
        this.claims = claims;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
