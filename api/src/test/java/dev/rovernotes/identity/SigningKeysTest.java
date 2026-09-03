package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The keys tokens are signed with, and what survives a restart.
 *
 * <p>These assertions are about persistence rather than cryptography: that a key is
 * created once and then reused, that what reaches the database is unreadable, and that
 * rotating does not invalidate tokens already issued.
 */
@SpringBootTest
@ActiveProfiles("local")
class SigningKeysTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    SigningKeys keys;

    @Autowired
    JdbcClient jdbc;

    private static final JWKSelector ANY_RSA =
            new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build());

    @BeforeEach
    void clear() {
        jdbc.sql("delete from signing_keys").update();
    }

    @Test
    void createsAKeyWhenThereIsNoneAndThenReusesIt() {
        keys.ensureActiveKey();
        String first = keys.activeKeyId().orElseThrow();

        keys.ensureActiveKey();

        // The failure this rules out is a new key on every request, which would leave a
        // JWKS response growing without bound and tokens signed by keys nobody kept.
        assertThat(keys.activeKeyId()).contains(first);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void writesAPrivateKeyTheDatabaseCannotRead() {
        keys.ensureActiveKey();

        byte[] stored = jdbc.sql("select private_key_encrypted from signing_keys")
                .query(byte[].class).single();

        // "d" is the private exponent in a JWK. Its presence would mean the key was
        // written in the clear, which is the whole point of the column being bytea.
        String asText = new String(stored, StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain("\"d\"").doesNotContain("RSA");
    }

    @Test
    void publishesThePublicHalfInTheClearBecauseItIsPublicAnyway() {
        keys.ensureActiveKey();

        String published = jdbc.sql("select public_key from signing_keys").query(String.class).single();

        assertThat(published).contains("\"kty\":\"RSA\"").doesNotContain("\"d\"");
    }

    @Test
    void keepsARetiredKeyPublishedSoTokensItSignedStillValidate() {
        keys.ensureActiveKey();
        String retired = keys.activeKeyId().orElseThrow();

        keys.retire(retired);
        keys.ensureActiveKey();
        String current = keys.activeKeyId().orElseThrow();

        assertThat(current).isNotEqualTo(retired);
        // Rotation that dropped the old key would reject every token still in circulation,
        // which is the failure rotation exists to avoid.
        assertThat(keys.publishedKeyIds()).contains(retired, current);
    }

    @Test
    void stopsPublishingAKeyOnceItHasExpired() {
        keys.ensureActiveKey();
        String expiring = keys.activeKeyId().orElseThrow();
        jdbc.sql("update signing_keys set expires_at = now() - interval '1 second' where kid = :kid")
                .param("kid", expiring).update();

        assertThat(keys.publishedKeyIds()).doesNotContain(expiring);
    }

    @Test
    void replacesAKeyThatHasExpiredRatherThanSigningWithNothing() {
        keys.ensureActiveKey();
        jdbc.sql("update signing_keys set expires_at = now() - interval '1 second'").update();

        keys.ensureActiveKey();

        assertThat(keys.activeKeyId()).isPresent();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void servesTheKeysAsAJwkSourceWithTheirPrivateHalfAvailableForSigning() {
        var selected = keys.get(ANY_RSA, null);

        assertThat(selected).hasSize(1);
        // A source that returned only public keys would publish correctly and fail to sign.
        assertThat(selected.getFirst().isPrivate()).isTrue();
        assertThat(selected.getFirst().getKeyID()).isEqualTo(keys.activeKeyId().orElseThrow());
    }

    private int count() {
        return jdbc.sql("select count(*) from signing_keys").query(Integer.class).single();
    }
}
