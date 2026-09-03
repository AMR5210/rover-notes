package dev.rovernotes.identity;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Registers the web interface as a client.
 *
 * <p>A browser application cannot keep a secret: anything shipped to it is readable by
 * whoever runs it. So this is a public client with no secret, and PKCE is required rather
 * than optional — without it, an authorization code intercepted on the redirect is enough
 * to obtain a token, because there is nothing else the exchange proves.
 *
 * <p>No consent screen. Consent exists so a person can decide what a third party may do on
 * their behalf; this client is the same application, and asking would be a dialog whose
 * only useful answer is yes.
 *
 * <p>Registered from code rather than left to be created by hand, because the flow does not
 * work without it and a missing row is a failure at sign-in rather than at startup. Clients
 * that are not this project's own belong in the registration endpoint instead, which is
 * deliberately not enabled yet.
 */
@Component
@DependsOnDatabaseInitialization
class WebClientRegistration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(WebClientRegistration.class);

    private final RegisteredClientRepository clients;
    private final String clientId;
    private final String[] redirectUris;

    WebClientRegistration(RegisteredClientRepository clients,
                          @Value("${rover.identity.web-client-id:rover-web}") String clientId,
                          @Value("${rover.identity.web-redirect-uris}") String[] redirectUris) {
        this.clients = clients;
        this.clientId = clientId;
        this.redirectUris = redirectUris.clone();
    }

    @Override
    public void afterPropertiesSet() {
        if (clients.findByClientId(clientId) != null) {
            return;
        }

        RegisteredClient.Builder client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName("Rover Notes web interface")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        // Short, because a public client has no way to hold anything
                        // longer-lived safely. The refresh grant is deliberately absent:
                        // Spring Authorization Server withholds refresh tokens from a
                        // client that authenticated with NONE, so registering the grant
                        // would advertise something that never arrives. A session is kept
                        // going by re-authorizing against the sign-in cookie instead.
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build());

        for (String uri : redirectUris) {
            client.redirectUri(uri.trim());
        }

        clients.save(client.build());
        log.info("Registered the web client {} with {} redirect URI(s)", clientId, redirectUris.length);
    }
}
