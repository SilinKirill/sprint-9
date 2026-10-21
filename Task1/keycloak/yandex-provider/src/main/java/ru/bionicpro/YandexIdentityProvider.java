package ru.bionicpro;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.sessions.AuthenticationSessionModel;

public final class YandexIdentityProvider
        extends AbstractOAuth2IdentityProvider<OAuth2IdentityProviderConfig>
        implements SocialIdentityProvider<OAuth2IdentityProviderConfig> {

    public YandexIdentityProvider(KeycloakSession session, OAuth2IdentityProviderConfig config) {
        super(session, config);
        config.setAuthorizationUrl("https://oauth.yandex.ru/authorize");
        config.setTokenUrl("https://oauth.yandex.ru/token");
    }

    @Override
    protected BrokeredIdentityContext doGetFederatedIdentity(String accessToken) {
        try {
            JsonNode profile = SimpleHttp.doGet("https://login.yandex.ru/info?format=json", session)
                    .header("Authorization", "OAuth " + accessToken).asJson();
            String id = getJsonProperty(profile, "id");
            if (id == null || !id.matches("[0-9]+")) {
                throw new IdentityBrokerException("Yandex did not return a valid subject.");
            }
            BrokeredIdentityContext identity = new BrokeredIdentityContext(id);
            identity.setUsername("yandex_" + id);
            identity.setIdpConfig(getConfig());
            identity.setIdp(this);
            // Only the stable login identifier is imported. Profile data is saved
            // by bionicpro-auth after a separate, explicit user consent.
            return identity;
        } catch (Exception e) {
            throw new IdentityBrokerException("Could not read the Yandex identity.", e);
        }
    }

    @Override
    public void authenticationFinished(AuthenticationSessionModel authSession, BrokeredIdentityContext context) {
        super.authenticationFinished(authSession, context);
        // Keycloak overwrites identity_provider_identity with the broker username.
        authSession.setUserSessionNote("yandex_subject", context.getId());
    }

    @Override
    protected String getDefaultScopes() {
        return "login:info login:email";
    }
}
