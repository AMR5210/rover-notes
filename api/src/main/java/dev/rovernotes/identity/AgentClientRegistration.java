package dev.rovernotes.identity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * What a client gets when it registers itself.
 *
 * <p>Registration is open, because that is how an agent connects to an MCP server it was
 * given only a URL for: it is refused, reads the resource metadata, follows it to the
 * authorization server, registers, and starts a code flow. Requiring an initial access
 * token would mean a person pasting a credential, which is the thing this replaces.
 *
 * <p>Open registration is not open access. Creating a client grants nothing on its own —
 * no token is issued until a person signs in and approves the request — so what a
 * registration produces is an application waiting to be authorised. This converter decides
 * what such an application is allowed to be. Only the redirect URIs, the name and a
 * filtered set of scopes are taken from the request; everything that bears on safety is
 * stated here:
 *
 * <ul>
 *   <li><b>Consent is required.</b> The web interface skips it because it is this project's
 *       own front end; anything that registered itself is not, and the person whose notes
 *       it would read should say so explicitly. This is the main control.</li>
 *   <li><b>PKCE is required and the client is public.</b> A secret issued to something that
 *       registered itself over HTTP is a secret with no owner worth protecting.</li>
 *   <li><b>Authorization code only.</b> Every other grant either skips the person or needs
 *       a credential this client does not have.</li>
 *   <li><b>Tokens are short.</b> The same fifteen minutes the interface gets.</li>
 *   <li><b>Scopes are assigned, not requested.</b> Spring Authorization Server refuses a
 *       registration that names any, so the set below is the whole of what a client
 *       gets.</li>
 * </ul>
 *
 * <p>Anyone reaching this endpoint can create a row, and an unused client is inert. The
 * path is covered by the request limit at ten attempts a minute, keyed by client address,
 * since callers here have no account to key on.
 */
@Component
class AgentClientRegistration implements Converter<OAuth2ClientRegistration, RegisteredClient> {

    /**
     * The scopes a self-registered client is given.
     *
     * <p>Assigned rather than requested, and not by choice here: Spring Authorization
     * Server refuses a registration that names any scope at all — "scope must not be set
     * during Dynamic Client Registration". That is the stronger rule, so this is the whole
     * of what such a client gets.
     *
     * <p>Both scopes identify the person rather than unlock anything. What an agent can
     * reach through the tools is decided by whose token it holds, which is why there is no
     * scope here that would widen that.
     */
    private static final List<String> ASSIGNED_SCOPES = List.of("openid", "profile");

    /**
     * Built rather than adapted from the request.
     *
     * <p>Spring Authorization Server's own converter is not reachable — it is a private
     * class inside the authentication provider — and building the client here is the better
     * shape anyway: what a self-registered client may be is stated in one place, in full,
     * rather than as a list of corrections applied to whatever arrived.
     */
    @Override
    public RegisteredClient convert(OAuth2ClientRegistration registration) {
        List<String> redirectUris = registration.getRedirectUris();
        if (redirectUris == null || redirectUris.isEmpty()) {
            // Without one there is nowhere to send a code, so the registration could never
            // be used. Refusing here is clearer than accepting it and failing later.
            return null;
        }

        RegisteredClient.Builder client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(UUID.randomUUID().toString())
                .clientName(registration.getClientName() == null
                        ? "Self-registered client"
                        : registration.getClientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build());

        redirectUris.forEach(client::redirectUri);

        ASSIGNED_SCOPES.forEach(client::scope);

        return client.build();
    }
}
