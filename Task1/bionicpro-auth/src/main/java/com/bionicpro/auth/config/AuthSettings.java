package com.bionicpro.auth.config;

import java.nio.file.Path;

public record AuthSettings(
        String origin,
        String issuer,
        String internalUrl,
        String realm,
        String clientId,
        String clientSecret,
        Path keyFile,
        Path profileDb,
        boolean yandexEnabled) {
    public String oidc() {
        return internalUrl + "/realms/" + realm + "/protocol/openid-connect";
    }
}
