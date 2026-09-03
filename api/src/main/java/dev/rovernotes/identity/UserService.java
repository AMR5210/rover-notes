package dev.rovernotes.identity;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts and their credentials.
 *
 * <p>Two rules run through this class. A password never exists here in plaintext beyond
 * the call that encodes or checks it, and nothing exposed from here reveals whether an
 * address has an account: {@link #findByEmail} returns an empty result for an unknown
 * address and for one that exists but cannot sign in, so a caller cannot distinguish
 * them by return value.
 */
@Service
public class UserService {

    private static final String COLUMNS = """
            id, email, password_hash, display_name, email_verified_at, disabled_at,
            failed_logins, locked_until
            """;

    /**
     * Creates the account, or does nothing if the address is taken.
     *
     * <p>{@code on conflict do nothing} rather than letting the unique constraint raise.
     * A caller that wants to carry on after a duplicate cannot do so if the insert threw:
     * the exception marks the surrounding transaction rollback-only, so the work it does
     * instead is discarded at commit. Returning no row keeps the decision in the caller.
     */
    private static final String INSERT = """
            insert into users (email, password_hash, display_name)
            values (cast(:email as citext), :hash, :name)
            on conflict (email) do nothing
            returning
            """ + COLUMNS;

    /**
     * The cast is required, not decorative.
     *
     * <p>The driver binds a string parameter as {@code varchar}. PostgreSQL has no
     * {@code citext = varchar} operator, so it coerces the column to {@code text} and
     * compares case-sensitively — the column's own case-insensitivity is lost at exactly
     * the point it matters. The unique index keeps working either way, which is what makes
     * this quiet: registration stays case-insensitive while sign-in silently stops being.
     */
    private static final String BY_EMAIL =
            "select " + COLUMNS + " from users where email = cast(:email as citext)";

    private static final String BY_ID = "select " + COLUMNS + " from users where id = :id";

    /**
     * Counts the failure and locks the account once the threshold is reached.
     *
     * <p>One statement rather than a read followed by a write: two callers failing at the
     * same moment would each read the same count and each write it back incremented once,
     * so a threshold of five could be crossed after considerably more attempts.
     */
    private static final String RECORD_FAILURE = """
            update users
               set failed_logins = failed_logins + 1,
                   locked_until  = case when failed_logins + 1 >= :threshold
                                        then now() + make_interval(secs => :lockSeconds)
                                        else locked_until end,
                   updated_at    = now()
             where id = :id
            """;

    private static final String CLEAR_FAILURES = """
            update users
               set failed_logins = 0, locked_until = null, updated_at = now()
             where id = :id and (failed_logins <> 0 or locked_until is not null)
            """;

    private static final String AUTHORITIES =
            "select authority from user_authorities where user_id = :id order by authority";

    private static final String GRANT = """
            insert into user_authorities (user_id, authority) values (:id, :authority)
            on conflict do nothing
            """;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final int failureThreshold;
    private final Duration lockout;

    UserService(JdbcClient jdbc, PasswordEncoder passwords,
                @Value("${rover.identity.failed-login-threshold:10}") int failureThreshold,
                @Value("${rover.identity.lockout:15m}") Duration lockout) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.failureThreshold = failureThreshold;
        this.lockout = lockout;
    }

    /**
     * Creates an account with the password encoded.
     *
     * <p>The account cannot sign in until its address is verified, which is what makes a
     * verification link worth sending. The address is compared case-insensitively by the
     * column type, so a second registration differing only in case is a duplicate.
     *
     * @return the new account, or empty if the address already has one. An existing
     *         account's password is never overwritten by a second registration.
     */
    @Transactional
    public Optional<UUID> register(String email, String rawPassword, String displayName) {
        return jdbc.sql(INSERT)
                .param("email", email == null ? "" : email.trim())
                .param("hash", passwords.encode(rawPassword))
                .param("name", displayName)
                .query(UserService::map)
                .optional()
                .map(AppUser::id);
    }

    /**
     * The account for an address, if it exists and could sign in.
     *
     * <p>Disabled, unverified and locked accounts are absent rather than returned with a
     * flag. A caller deciding what to do with each state would end up describing the
     * difference in its response, which is how a login endpoint becomes a way to test
     * whether an address is registered.
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql(BY_EMAIL)
                .param("email", email == null ? "" : email.trim())
                .query(UserService::map)
                .optional()
                .filter(user -> !user.disabled())
                .filter(AppUser::verified)
                .filter(user -> !user.lockedAt(Instant.now()));
    }

    /** Unfiltered, for callers that already hold an identifier rather than a claim to one. */
    @Transactional(readOnly = true)
    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql(BY_ID).param("id", id).query(UserService::map).optional();
    }

    @Transactional(readOnly = true)
    public List<String> authorities(UUID id) {
        return jdbc.sql(AUTHORITIES).param("id", id).query(String.class).list();
    }

    @Transactional
    public void grant(UUID id, String authority) {
        jdbc.sql(GRANT).param("id", id).param("authority", authority).update();
    }

    /**
     * Counts a failed sign-in and locks the account once the threshold is reached.
     *
     * <p>The lock has a duration rather than being permanent. A permanent lock lets anyone
     * who knows an address deny its owner access by failing to sign in as them; a lapse
     * bounds that to the duration while still making an online guessing attack impractical.
     */
    @Transactional
    public void recordFailedSignIn(UUID id) {
        jdbc.sql(RECORD_FAILURE)
                .param("id", id)
                .param("threshold", failureThreshold)
                .param("lockSeconds", lockout.toSeconds())
                .update();
    }

    /** Clears the failure count after a sign-in that succeeded. */
    @Transactional
    public void recordSuccessfulSignIn(UUID id) {
        jdbc.sql(CLEAR_FAILURES).param("id", id).update();
    }

    /**
     * Replaces the stored password.
     *
     * <p>Encoded on the way in like any other, so a reset does not produce a hash in a
     * different shape from a registration.
     */
    @Transactional
    public void changePassword(UUID id, String rawPassword) {
        jdbc.sql("update users set password_hash = :hash, updated_at = now() where id = :id")
                .param("id", id)
                .param("hash", passwords.encode(rawPassword))
                .update();
    }

    /** Marks the address proven, which is what allows the account to sign in. */
    @Transactional
    public void markEmailVerified(UUID id) {
        jdbc.sql("update users set email_verified_at = now(), updated_at = now() "
                + "where id = :id and email_verified_at is null").param("id", id).update();
    }

    private static AppUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                instant(rs.getObject("email_verified_at", OffsetDateTime.class)),
                instant(rs.getObject("disabled_at", OffsetDateTime.class)),
                rs.getInt("failed_logins"),
                instant(rs.getObject("locked_until", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
