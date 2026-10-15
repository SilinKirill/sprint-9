package ru.bionicpro;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.Details;
import org.keycloak.sessions.AuthenticationSessionModel;

public class YandexIdentityProviderTest {
    @Test
    public void externalSubjectSurvivesKeycloakUsernameNote() {
        Map<String, String> notes = new HashMap<>();
        AuthenticationSessionModel session = (AuthenticationSessionModel) Proxy.newProxyInstance(
                AuthenticationSessionModel.class.getClassLoader(),
                new Class<?>[] {AuthenticationSessionModel.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setUserSessionNote")) {
                        notes.put((String) arguments[0], (String) arguments[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BrokeredIdentityContext identity = new BrokeredIdentityContext("12345");
        identity.setUsername("yandex_12345");
        YandexIdentityProvider provider = new YandexIdentityProvider(null, new OAuth2IdentityProviderConfig());

        provider.authenticationFinished(session, identity);
        // IdentityBrokerService writes these notes after the provider callback.
        session.setUserSessionNote(Details.IDENTITY_PROVIDER, "yandex");
        session.setUserSessionNote(Details.IDENTITY_PROVIDER_USERNAME, identity.getUsername());

        assertEquals("12345", notes.get("yandex_subject"));
        assertEquals("yandex_12345", notes.get(Details.IDENTITY_PROVIDER_USERNAME));
    }
}
