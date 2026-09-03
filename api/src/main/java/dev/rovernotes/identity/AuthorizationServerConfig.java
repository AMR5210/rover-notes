package dev.rovernotes.identity;

import java.util.UUID;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This service as the issuer of the tokens it validates. See docs/ARCHITECTURE.md.
 *
 * <p>Runs in the same deployable as the resource server rather than beside it, which is
 * the arrangement docs/ARCHITECTURE.md chose for every other part of this system. The barrier that
 * makes it safe is the module boundary rather than a process boundary: only
 * {@code dev.rovernotes.identity} reaches {@code SigningKeys}, and
 * {@code ModularityTests} fails the build if that stops being true. The seam is where a
 * separate deployable would be cut if one is ever wanted.
 *
 * <p>Three services are backed by the database rather than by memory. In-memory versions
 * are the Spring samples' default and would mean a restart invalidating every session and
 * two instances disagreeing about which authorizations exist.
 */
@Configuration
class AuthorizationServerConfig {

    /** RFC 7591's endpoint. The OIDC one at /connect/register keeps its default guard. */
    private static final String REGISTRATION_ENDPOINT = "/oauth2/register";

    /**
     * Ahead of the application's own chain, and matching only the protocol endpoints.
     *
     * <p>Order matters and the matcher is what makes it safe: this chain claims
     * {@code /oauth2/**}, {@code /.well-known/**} and the OIDC endpoints, and declines
     * everything else, so {@code SecurityConfig} still governs {@code /api/**} and
     * {@code /mcp}. Putting it second would let the catch-all deny the token endpoint.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerChain(
            HttpSecurity http, AgentClientRegistration agentClients,
            dev.rovernotes.RateLimitFilter rateLimit) throws Exception {
        // Constructed rather than obtained from a static factory: 7.x moved these classes
        // into spring-security-config and the factory that older samples use is not there.
        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();

        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        // Publishes /.well-known/openid-configuration and the userinfo and
                        // client registration endpoints. The discovery document is what an
                        // agent reads to find out how to obtain a token, which is the flow
                        // Claude Code runs against a remote MCP server by default.
                        .oidc(Customizer.withDefaults())
                        // RFC 7591, open. An agent handed only a URL cannot be pre-registered
                        // by definition, and requiring an initial access token would put a
                        // person back in the loop pasting a credential — which is what the
                        // whole discovery chain exists to remove. What a self-registered
                        // client may be is constrained by AgentClientRegistration, and
                        // consent means a person still approves every one of them.
                        .clientRegistrationEndpoint(registration -> registration
                                .openRegistrationAllowed(true)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider dcr) {
                                        dcr.setRegisteredClientConverter(agentClients);
                                    }
                                }))))
                .authorizeHttpRequests(auth -> auth
                        // Open registration has to be said here as well as configured on
                        // the endpoint. The registration filter sits behind the
                        // authorization rules, so without this an anonymous caller is sent
                        // to the sign-in form and openRegistrationAllowed never applies —
                        // which is the one caller registration exists for.
                        .requestMatchers(REGISTRATION_ENDPOINT).permitAll()
                        .anyRequest().authenticated())
                // Without this, an unauthenticated caller at /oauth2/authorize is refused
                // rather than asked to sign in, and the code grant cannot start. The form
                // it points at is served by SignInConfig on the next chain.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new org.springframework.security.web.authentication.
                                LoginUrlAuthenticationEntryPoint("/login")))
                // The protocol's own endpoints are form-encoded and use a session during
                // the authorization code exchange, so this chain is not stateless the way
                // the resource-server chain is.
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                // Client registration is open by design, which makes it the one endpoint
                // here an unauthenticated caller can create state through. Counted by
                // client address, in the same bucket as account creation: both are ways of
                // getting a new identity into the system without holding one first.
                .addFilterAfter(rateLimit,
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class);

        return http.build();
    }

    /**
     * Signed tokens carry the account id as their subject.
     *
     * <p>Nothing is done here to make that true: the authenticated principal's name is
     * already the account id, because {@link IdentityUserDetails#getUsername()} returns it.
     * This customiser adds the claims that are this project's own, and asserts the subject
     * is what the rest of the schema assumes so that a change to the principal fails here
     * rather than silently writing an unusable {@code owner_id}.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(UserService users) {
        return context -> {
            String subject = context.getPrincipal().getName();
            UUID accountId;
            try {
                accountId = UUID.fromString(subject);
            } catch (IllegalArgumentException notAnId) {
                throw new IllegalStateException(
                        "the token subject must be the account id, which every owner_id "
                                + "column expects; got: " + subject, notAnId);
            }
            // Looked up rather than carried on the principal. The principal is serialised
            // into oauth2_authorization.attributes, so anything held on it is written to a
            // second table for as long as the authorization lives.
            users.findById(accountId)
                    .ifPresent(user -> context.getClaims().claim("email", user.email()));
        };
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc,
                                                    RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * The decoder the resource-server side uses, built from the same key source that signs.
     *
     * <p>Without this the two halves would agree only by both reaching the JWKS endpoint
     * over the network, which means this service making an HTTP request to itself at
     * startup and failing if it is not yet accepting connections.
     */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @org.springframework.beans.factory.annotation.Value("${rover.identity.issuer}")
            String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

}
