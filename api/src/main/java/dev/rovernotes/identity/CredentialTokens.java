package dev.rovernotes.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single-use secrets behind email verification and password reset.
 *
 * <p>Only a digest of the token is stored, so the table cannot be used to complete either
 * flow. That also means a token cannot be looked up by anything except itself: verifying a
 * presented value hashes it and finds the row, and there is no path from a row back to the
 * value that produced it.
 *
 * <p>Redemption is a conditional update rather than a read followed by a write. Two
 * requests carrying the same token arrive at the same statement, exactly one changes a
 * row, and the other is told the token is spent — which is what "single use" has to mean
 * when a link is clicked twice.
 *
 * <p>Issuing a token invalidates any outstanding token for the same purpose. A reset link
 * that was requested and then requested again should leave one working link, not two.
 */
@Service
class CredentialTokens {

    /** 256 bits from a cryptographic source. Guessing is not a threat model this needs. */
    private static final int TOKEN_BYTES = 32;

    private static final String INVALIDATE_OUTSTANDING = """
            update user_tokens set consumed_at = now()
            where user_id = :user and purpose = :purpose and consumed_at is null
            """;

    private static final String INSERT = """
            insert into user_tokens (user_id, purpose, token_hash, expires_at)
            values (:user, :purpose, :hash, now() + make_interval(secs => :ttlSeconds))
            """;

    /**
     * Spends the token and reports whose it was.
     *
     * <p>The conditions are all in the statement. Expiry, prior use and purpose are checked
     * where the row is locked, so a token cannot pass a check in the application and then
     * be redeemed by another request before the update lands.
     */
    private static final String REDEEM = """
            update user_tokens set consumed_at = now()
            where token_hash = :hash
              and purpose = :purpose
              and consumed_at is null
              and expires_at > now()
            returning user_id
            """;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();
    private final Duration verificationTtl;
    private final Duration resetTtl;

    CredentialTokens(JdbcClient jdbc,
                     @Value("${rover.identity.verification-token-ttl:24h}") Duration verificationTtl,
                     @Value("${rover.identity.reset-token-ttl:1h}") Duration resetTtl) {
        this.jdbc = jdbc;
        this.verificationTtl = verificationTtl;
        this.resetTtl = resetTtl;
    }

    enum Purpose {
        EMAIL_VERIFICATION("email_verification"),
        PASSWORD_RESET("password_reset");

        private final String stored;

        Purpose(String stored) {
            this.stored = stored;
        }

        String stored() {
            return stored;
        }
    }

    /**
     * Creates a token and returns it. This is the only time its value exists.
     *
     * @return the token to put in a link; it cannot be recovered afterwards
     */
    @Transactional
    String issue(UUID userId, Purpose purpose) {
        jdbc.sql(INVALIDATE_OUTSTANDING)
                .param("user", userId)
                .param("purpose", purpose.stored())
                .update();

        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        jdbc.sql(INSERT)
                .param("user", userId)
                .param("purpose", purpose.stored())
                .param("hash", digest(token))
                .param("ttlSeconds", ttl(purpose).toSeconds())
                .update();

        return token;
    }

    /**
     * Spends a token, if it is one.
     *
     * <p>An unknown token, an expired one, one already used, and one issued for the other
     * purpose all return empty. A caller cannot tell them apart, which matters because the
     * difference between "expired" and "never existed" is information about someone else's
     * account.
     */
    @Transactional
    Optional<UUID> redeem(String token, Purpose purpose) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql(REDEEM)
                .param("hash", digest(token))
                .param("purpose", purpose.stored())
                .query(UUID.class)
                .optional();
    }

    private Duration ttl(Purpose purpose) {
        return purpose == Purpose.PASSWORD_RESET ? resetTtl : verificationTtl;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }
}
