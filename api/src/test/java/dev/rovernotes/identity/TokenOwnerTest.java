package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.rovernotes.CurrentOwner;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a valid token maps to.
 *
 * <p>The negative direction was already covered: {@code SecurityConfigTest} shows a request
 * carrying no token reaches nothing. This is the other half, and until now it was the gap
 * — the mapping from a token to an owner is what every {@code owner_id} in the schema rests
 * on, and it had never been exercised with a token that was actually issued.
 *
 * <p>The token here is signed by the service's own key and read back through the service's
 * own decoder, so the round trip covers signing, publication and validation rather than a
 * {@link Jwt} assembled in the test. Token validation itself is Spring Security's contract;
 * what is this project's is that the subject is the account id.
 */
@SpringBootTest
@ActiveProfiles("local")
class TokenOwnerTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    JWKSource<SecurityContext> keys;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    CurrentOwner currentOwner;

    @Autowired
    UserService users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    org.springframework.security.core.userdetails.UserDetailsService userDetails;

    private JwtEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new NimbusJwtEncoder(keys);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UUID account(String email) {
        UUID id = users.register(email, "a-password-nobody-uses", "Test").orElseThrow();
        users.markEmailVerified(id);
        return id;
    }

    /** A token as this service would issue it, signed with the current key. */
    private String issue(UUID subject, Instant expiry) {
        return issue(subject, Instant.now(), expiry);
    }

    private String issue(UUID subject, Instant issuedAt, Instant expiry) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://rover.example")
                .subject(subject.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiry)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private void present(String token) {
        Jwt decoded = decoder.decode(token);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(decoded));
    }

    @Test
    void resolvesTheOwnerOfARequestFromTheTokenSubject() {
        UUID id = account("holder@example.com");

        present(issue(id, Instant.now().plus(1, ChronoUnit.HOURS)));

        // This is the assertion every owner_id column depends on. If the subject were the
        // email address, or a provider-shaped identifier, every write would either fail on
        // the foreign key or attribute data to the wrong account.
        assertThat(currentOwner.id()).isEqualTo(id);
    }

    @Test
    void aTokenThisServiceSignedIsAcceptedByItsOwnDecoder() {
        UUID id = account("roundtrip@example.com");

        Jwt decoded = decoder.decode(issue(id, Instant.now().plus(1, ChronoUnit.HOURS)));

        // Signing and validation share one key source, so this fails if the decoder were
        // wired to a different set of keys than the encoder signs with.
        assertThat(decoded.getSubject()).isEqualTo(id.toString());
        // The key identifier is what lets a verifier pick the right key after a rotation.
        assertThat(decoded.getHeaders()).containsKey("kid");
    }

    /**
     * Signing in by address produces a principal named by account id.
     *
     * <p>That name becomes the {@code sub} claim, so this is what keeps a token's subject
     * a value an {@code owner_id} column can hold. {@code AuthorizationCodeGrantTest}
     * exercises it through a real grant; this asserts it at the lookup, where the failure
     * would be legible rather than arriving as a foreign key violation.
     */
    @Test
    void makesTheAccountIdTheAuthenticatedPrincipalRatherThanTheAddress() {
        UUID id = account("principal@example.com");

        var principal = userDetails.loadUserByUsername("principal@example.com");

        assertThat(principal.getUsername()).isEqualTo(id.toString());
        assertThat(principal.getUsername()).isNotEqualTo("principal@example.com");
    }

    @Test
    void refusesATokenSignedBySomethingElse() {
        UUID id = account("forged@example.com");
        String forged = new NimbusJwtEncoder(foreignKeySource())
                .encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                        .issuer("https://rover.example")
                        .subject(id.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                        .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesATokenThatHasExpired() {
        // Issued in the past and already expired. JwtClaimsSet refuses to build a token
        // that expires before it was issued, so both instants move rather than one.
        UUID id = account("expired@example.com");
        String stale = issue(id, Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> decoder.decode(stale)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesATokenWhoseBodyHasBeenEdited() {
        UUID mine = account("mine@example.com");
        String token = issue(mine, Instant.now().plus(1, ChronoUnit.HOURS));

        // Swap a character in the payload segment. The signature no longer matches, which
        // is what stops a holder rewriting the subject into somebody else's account.
        String[] parts = token.split("\\.");
        parts[1] = parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A");
        String edited = String.join(".", parts);

        assertThatThrownBy(() -> decoder.decode(edited)).isInstanceOf(JwtException.class);
    }

    @Test
    void fallsBackToTheDevelopmentOwnerOnlyWhenNoTokenIsPresent() {
        // The local profile's fallback, which is what lets the stack run unauthenticated.
        // It applies in the absence of a principal, not in preference to one.
        SecurityContextHolder.clearContext();

        assertThat(currentOwner.id())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void aTokenTakesPrecedenceOverTheDevelopmentFallback() {
        UUID id = account("precedence@example.com");

        present(issue(id, Instant.now().plus(1, ChronoUnit.HOURS)));

        assertThat(currentOwner.id()).isNotEqualTo(
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(currentOwner.id()).isEqualTo(id);
    }

    private static JWKSource<SecurityContext> foreignKeySource() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(key));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
