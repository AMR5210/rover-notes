package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Accounts and what can be learned about them.
 *
 * <p>The threshold is lowered to three here so the lockout can be reached without
 * thirty round trips. The behaviour under test is the transition, not the number.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "rover.identity.failed-login-threshold=3",
        "rover.identity.lockout=15m"})
class UserServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    UserService users;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("delete from users").update();
    }

    /** Registration alone cannot sign in, so most tests want a verified account. */
    private UUID verified(String email, String password) {
        UUID id = users.register(email, password, "Test Account").orElseThrow();
        users.markEmailVerified(id);
        return id;
    }

    @Test
    void storesAnEncodedPasswordRatherThanTheOneSupplied() {
        UUID id = users.register("ada@example.com", "correct horse battery staple", "Ada").orElseThrow();

        String stored = jdbc.sql("select password_hash from users where id = :id")
                .param("id", id).query(String.class).single();

        assertThat(stored).doesNotContain("correct horse battery staple");
        // The prefix is what lets the parameters be raised later without invalidating
        // hashes already written.
        assertThat(stored).startsWith("{argon2}$argon2id$");
        assertThat(passwords.matches("correct horse battery staple", stored)).isTrue();
    }

    @Test
    void usesTheArgon2ParametersThisProjectChose() {
        // Read back from the hash rather than from the configuration that produced it,
        // so a library default moving underneath the encoder is visible here.
        UUID id = users.register("params@example.com", "whatever", null).orElseThrow();
        String stored = jdbc.sql("select password_hash from users where id = :id")
                .param("id", id).query(String.class).single();

        assertThat(stored).contains("$m=19456,t=2,p=1$");
    }

    @Test
    void treatsAnAddressDifferingOnlyInCaseAsTheSameAccount() {
        users.register("Ada@Example.com", "first", null);

        // Empty rather than an exception. A caller that wants to carry on after a
        // duplicate cannot do so if the insert threw, because the exception marks the
        // surrounding transaction rollback-only and discards whatever it did instead.
        assertThat(users.register("ada@EXAMPLE.com", "second", null)).isEmpty();
    }

    @Test
    void findsAVerifiedAccountByAnAddressInAnyCase() {
        UUID id = verified("ada@example.com", "secret");

        assertThat(users.findByEmail("ADA@Example.com")).map(AppUser::id).contains(id);
    }

    @Test
    void hidesAnAccountWhoseAddressIsNotYetProven() {
        users.register("unverified@example.com", "secret", null);

        // Absent rather than present-and-flagged: a caller with a flag ends up explaining
        // the difference in its response, which is how a form becomes a way to find out
        // which addresses are registered.
        assertThat(users.findByEmail("unverified@example.com")).isEmpty();
    }

    @Test
    void hidesADisabledAccount() {
        UUID id = verified("disabled@example.com", "secret");
        jdbc.sql("update users set disabled_at = now() where id = :id").param("id", id).update();

        assertThat(users.findByEmail("disabled@example.com")).isEmpty();
    }

    @Test
    void answersTheSameWayForAnAddressWithNoAccount() {
        assertThat(users.findByEmail("nobody@example.com")).isEmpty();
        assertThat(users.findByEmail(null)).isEmpty();
        assertThat(users.findByEmail("  ")).isEmpty();
    }

    @Test
    void locksAnAccountOnceTheFailureThresholdIsReached() {
        UUID id = verified("target@example.com", "secret");

        users.recordFailedSignIn(id);
        users.recordFailedSignIn(id);
        assertThat(users.findByEmail("target@example.com")).isNotEmpty();

        users.recordFailedSignIn(id);
        assertThat(users.findByEmail("target@example.com")).isEmpty();
    }

    @Test
    void lapsesTheLockRatherThanHoldingItForever() {
        // A permanent lock lets anyone who knows an address deny its owner access simply
        // by failing to sign in as them. The lapse bounds that.
        UUID id = verified("lapsing@example.com", "secret");
        for (int attempt = 0; attempt < 3; attempt++) {
            users.recordFailedSignIn(id);
        }
        assertThat(users.findByEmail("lapsing@example.com")).isEmpty();

        jdbc.sql("update users set locked_until = now() - interval '1 second' where id = :id")
                .param("id", id).update();

        assertThat(users.findByEmail("lapsing@example.com")).isNotEmpty();
    }

    @Test
    void clearsTheCountAfterASignInThatSucceeded() {
        UUID id = verified("recovering@example.com", "secret");
        users.recordFailedSignIn(id);
        users.recordFailedSignIn(id);

        users.recordSuccessfulSignIn(id);

        // Without the reset, two failures today plus one tomorrow would lock an account
        // that was never under attack.
        assertThat(users.findById(id)).map(AppUser::failedLogins).contains(0);
        users.recordFailedSignIn(id);
        assertThat(users.findByEmail("recovering@example.com")).isNotEmpty();
    }

    @Test
    void grantsAuthoritiesWithoutDuplicatingThem() {
        UUID id = verified("granted@example.com", "secret");

        users.grant(id, "ROLE_USER");
        users.grant(id, "ROLE_USER");
        users.grant(id, "ROLE_ADMIN");

        assertThat(users.authorities(id)).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void verifyingAnAddressIsNotSomethingThatHappensTwice() {
        UUID id = users.register("once@example.com", "secret", null).orElseThrow();
        users.markEmailVerified(id);
        var first = users.findById(id).orElseThrow().emailVerifiedAt();

        users.markEmailVerified(id);

        assertThat(users.findById(id).orElseThrow().emailVerifiedAt()).isEqualTo(first);
    }
}
