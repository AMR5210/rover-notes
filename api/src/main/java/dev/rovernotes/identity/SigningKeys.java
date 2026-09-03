package dev.rovernotes.identity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The keys this service signs tokens with, held in the database.
 *
 * <p>Spring Authorization Server generates a key in memory when none is supplied, which is
 * correct for a sample and wrong for a deployment in two ways: every restart invalidates
 * every token already issued, and two instances sign with different keys while publishing
 * only their own, so a token minted by one is rejected by the other. Storing the key makes
 * both go away.
 *
 * <p>The private half is encrypted before it is written; see {@link KeyEncryption}. The
 * public half is stored in the clear because it is published at the JWKS endpoint anyway.
 *
 * <p>Rotation is additive. A new key becomes the one that signs, and the previous key
 * stays published until the longest-lived token it signed has expired, so rotating does
 * not invalidate sessions. Retiring a key is therefore two steps separated by time, which
 * is why {@code retired_at} and {@code expires_at} are separate columns.
 */
@Component
class SigningKeys implements JWKSource<SecurityContext> {

    private static final String ACTIVE = """
            select kid, public_key, private_key_encrypted
            from signing_keys
            where retired_at is null and expires_at > now()
            order by created_at desc
            limit 1
            """;

    /**
     * Everything a token in circulation might have been signed with.
     *
     * <p>Retired keys are included while they remain unexpired. Leaving them out would
     * reject tokens that are still valid, which is the failure rotation exists to avoid.
     */
    private static final String PUBLISHABLE = """
            select kid, public_key, private_key_encrypted
            from signing_keys
            where expires_at > now()
            order by retired_at nulls first, created_at desc
            """;

    private static final String INSERT = """
            insert into signing_keys (kid, algorithm, public_key, private_key_encrypted, expires_at)
            values (:kid, 'RS256', :public, :private, now() + make_interval(secs => :lifetimeSeconds))
            """;

    private final JdbcClient jdbc;
    private final KeyEncryption encryption;
    private final Duration lifetime;

    SigningKeys(JdbcClient jdbc, KeyEncryption encryption,
                @Value("${rover.identity.signing-key-lifetime:90d}") Duration lifetime) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.lifetime = lifetime;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        ensureActiveKey();
        List<JWK> keys = jdbc.sql(PUBLISHABLE).query(this::toKey).list();
        return selector.select(new JWKSet(keys));
    }

    /**
     * Creates the first key if there is none, or a replacement once the last has expired.
     *
     * <p>Two instances starting at once can both find no key and both insert. The primary
     * key on {@code kid} does not prevent that, since each generates its own, so the loser
     * is not detected here — both keys are valid, both are published, and the extra one is
     * retired by the next rotation. Correctness does not depend on there being exactly one.
     */
    @Transactional
    void ensureActiveKey() {
        boolean present = jdbc.sql(ACTIVE).query(this::toKey).optional().isPresent();
        if (present) {
            return;
        }
        RSAKey generated = generate();
        jdbc.sql(INSERT)
                .param("kid", generated.getKeyID())
                .param("public", generated.toPublicJWK().toJSONString())
                .param("private", encryption.encrypt(generated.toJSONString()))
                .param("lifetimeSeconds", lifetime.toSeconds())
                .update();
    }

    /** The key currently signing, which the token customiser needs by identifier. */
    @Transactional(readOnly = true)
    java.util.Optional<String> activeKeyId() {
        return jdbc.sql(ACTIVE).query((rs, row) -> rs.getString("kid")).optional();
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception failure) {
            throw new IllegalStateException("could not generate a signing key", failure);
        }
    }

    private JWK toKey(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            return RSAKey.parse(encryption.decrypt(rs.getBytes("private_key_encrypted")));
        } catch (java.text.ParseException malformed) {
            throw new IllegalStateException(
                    "signing key " + rs.getString("kid") + " could not be read", malformed);
        }
    }

    /** Retires a key so it stops signing while remaining published until it expires. */
    @Transactional
    void retire(String kid) {
        jdbc.sql("update signing_keys set retired_at = now() where kid = :kid and retired_at is null")
                .param("kid", kid)
                .update();
    }

    /** Exposed for the rotation check; unexpired keys are what a JWKS response contains. */
    @Transactional(readOnly = true)
    List<String> publishedKeyIds() {
        return jdbc.sql("select kid from signing_keys where expires_at > now() "
                + "order by retired_at nulls first, created_at desc")
                .query(String.class)
                .list();
    }
}
