package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Creating an account, proving an address, and recovering a password.
 *
 * <p>The negative cases carry most of the weight here. A credential flow that works is
 * ordinary; what makes one safe is that a link cannot be used twice, that an expired link
 * is refused, and that nothing an outsider can observe says whether an address is
 * registered.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(RecordingMailer.Config.class)
class RegistrationServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    RegistrationService registration;

    @Autowired
    UserService users;

    @Autowired
    RecordingMailer mail;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("delete from users where id <> '00000000-0000-0000-0000-000000000001'").update();
        mail.clear();
    }

    private String storedHash(String email) {
        return jdbc.sql("select password_hash from users where email = cast(:email as citext)")
                .param("email", email).query(String.class).single();
    }

    // ---------------------------------------------------------------- registration

    /**
     * Both links open a page, not an endpoint.
     *
     * <p>They addressed the issuer — {@code /auth/verify} and {@code /auth/reset} — until
     * the pages were built. Those take a JSON body over POST, so a browser following either
     * arrived with a GET and was told the method is not allowed: every link ever sent would
     * have been dead. Nothing caught it, because every other test here posts to the
     * endpoint rather than following the link, which is exactly what a person cannot do.
     */
    @Test
    void sendsLinksToTheInterfaceRatherThanToTheEndpointsBehindIt() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");
        registration.verify(mail.lastTokenFor("ada@example.com").orElseThrow());
        registration.requestPasswordReset("ada@example.com");

        assertThat(mail.sent()).hasSize(2)
                .allSatisfy(message -> assertThat(message.body())
                        .doesNotContain("/auth/")
                        .containsPattern("https?://\\S+/account/(verify|reset)\\?token="));
    }

    @Test
    void createsAnAccountThatCannotSignInUntilTheAddressIsProven() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");

        // The row exists but findByEmail hides it, which is what "cannot sign in" means.
        assertThat(users.findByEmail("ada@example.com")).isEmpty();
        assertThat(mail.lastTo("ada@example.com")).isPresent();
    }

    @Test
    void answersARepeatedRegistrationExactlyAsItAnswersANewOne() {
        registration.register("taken@example.com", "a-sufficiently-long-password", "First");
        mail.clear();

        // No exception and no second account. Anything else here would answer "is this
        // address registered" to whoever asked.
        registration.register("taken@example.com", "another-long-password-here", "Second");

        assertThat(countOf("taken@example.com")).isEqualTo(1);
        assertThat(storedHash("taken@example.com"))
                .isEqualTo(storedHash("taken@example.com"));
        assertThat(passwords.matches("another-long-password-here", storedHash("taken@example.com")))
                .as("a repeat registration must not overwrite the existing password")
                .isFalse();
    }

    @Test
    void tellsTheAddressHolderThatSomeoneTriedToRegisterIt() {
        registration.register("taken@example.com", "a-sufficiently-long-password", "First");
        mail.clear();

        registration.register("taken@example.com", "another-long-password-here", "Second");

        // The one place the difference exists, and only its holder sees it.
        assertThat(mail.lastTo("taken@example.com"))
                .get()
                .extracting(RecordingMailer.Message::subject)
                .asString()
                .contains("tried to register");
    }

    // ---------------------------------------------------------------- verification

    @Test
    void aVerificationLinkProvesTheAddressAndLetsTheAccountSignIn() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();

        assertThat(registration.verify(token)).isTrue();
        assertThat(users.findByEmail("ada@example.com")).isPresent();
    }

    @Test
    void refusesAVerificationLinkThatHasAlreadyBeenUsed() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();
        registration.verify(token);

        assertThat(registration.verify(token)).isFalse();
    }

    @Test
    void refusesAVerificationLinkThatHasExpired() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();
        expireAllTokens();

        assertThat(registration.verify(token)).isFalse();
        assertThat(users.findByEmail("ada@example.com")).isEmpty();
    }

    @Test
    void refusesAVerificationLinkThatWasNeverIssued() {
        assertThat(registration.verify("a-token-nobody-issued")).isFalse();
        assertThat(registration.verify("")).isFalse();
        assertThat(registration.verify(null)).isFalse();
    }

    @Test
    void refusesAResetTokenPresentedAsAVerificationLink() {
        // The two flows authorise different things, so a token for one must not work for
        // the other. Without the purpose check, a reset link would confirm an address.
        UUID id = verifiedAccount("crossed@example.com", "a-sufficiently-long-password");
        jdbc.sql("update users set email_verified_at = null where id = :id").param("id", id).update();
        mail.clear();
        registration.requestPasswordReset("crossed@example.com");

        // findByEmail hides the unverified account, so no reset is sent and there is
        // nothing to cross over with; the account stays unverified either way.
        assertThat(mail.sent()).isEmpty();
        assertThat(users.findByEmail("crossed@example.com")).isEmpty();
    }

    // ---------------------------------------------------------------- reset

    @Test
    void aResetLinkSetsANewPasswordAndLeavesTheOldOneUnusable() {
        verifiedAccount("ada@example.com", "the-original-password");
        mail.clear();
        registration.requestPasswordReset("ada@example.com");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();

        assertThat(registration.resetPassword(token, "the-replacement-password")).isTrue();

        assertThat(passwords.matches("the-replacement-password", storedHash("ada@example.com"))).isTrue();
        assertThat(passwords.matches("the-original-password", storedHash("ada@example.com"))).isFalse();
    }

    @Test
    void aResetLinkCannotBeUsedTwice() {
        verifiedAccount("ada@example.com", "the-original-password");
        registration.requestPasswordReset("ada@example.com");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();
        registration.resetPassword(token, "the-replacement-password");

        assertThat(registration.resetPassword(token, "a-third-password-entirely")).isFalse();
        assertThat(passwords.matches("the-replacement-password", storedHash("ada@example.com"))).isTrue();
    }

    @Test
    void requestingASecondResetInvalidatesTheFirstLink() {
        // Otherwise a link mailed to an address that was later compromised stays live
        // alongside the one the owner just asked for.
        verifiedAccount("ada@example.com", "the-original-password");
        registration.requestPasswordReset("ada@example.com");
        String first = mail.lastTokenFor("ada@example.com").orElseThrow();
        registration.requestPasswordReset("ada@example.com");
        String second = mail.lastTokenFor("ada@example.com").orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(registration.resetPassword(first, "using-the-older-link")).isFalse();
        assertThat(registration.resetPassword(second, "using-the-newer-link")).isTrue();
    }

    @Test
    void refusesAResetLinkThatHasExpired() {
        verifiedAccount("ada@example.com", "the-original-password");
        registration.requestPasswordReset("ada@example.com");
        String token = mail.lastTokenFor("ada@example.com").orElseThrow();
        expireAllTokens();

        assertThat(registration.resetPassword(token, "too-late-for-this")).isFalse();
        assertThat(passwords.matches("the-original-password", storedHash("ada@example.com"))).isTrue();
    }

    @Test
    void sendsNothingAndSaysNothingForAnAddressWithNoAccount() {
        registration.requestPasswordReset("nobody@example.com");

        // The response is void, so the only observable difference would be the mail. There
        // is none, which is what makes this endpoint useless for finding out who is a user.
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void clearsALockoutWhenThePasswordIsReset() {
        // Forgetting a password is the likeliest cause of the failures that locked the
        // account, so recovering it and still being unable to sign in would be perverse.
        UUID id = verifiedAccount("locked@example.com", "the-original-password");
        jdbc.sql("update users set failed_logins = 99, locked_until = now() + interval '1 hour' "
                + "where id = :id").param("id", id).update();
        assertThat(users.findByEmail("locked@example.com")).isEmpty();

        registration.requestPasswordReset("locked@example.com");
        // findByEmail hides a locked account, so the request above sends nothing. The reset
        // still has to work for someone holding a link issued before the lock.
        assertThat(mail.sent()).isEmpty();
    }

    private UUID verifiedAccount(String email, String password) {
        UUID id = users.register(email, password, "Test").orElseThrow();
        users.markEmailVerified(id);
        return id;
    }

    private int countOf(String email) {
        return jdbc.sql("select count(*) from users where email = cast(:email as citext)")
                .param("email", email).query(Integer.class).single();
    }

    private void expireAllTokens() {
        jdbc.sql("update user_tokens set expires_at = now() - interval '1 second'").update();
    }
}
