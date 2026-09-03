package dev.rovernotes.identity;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating an account, proving the address, and recovering from a forgotten password.
 *
 * <p>One rule shapes every method here: nothing observable from outside distinguishes an
 * address that has an account from one that does not. Registering with a taken address,
 * requesting a reset for an unknown address, and redeeming a token that never existed all
 * return the same thing a successful call returns. The flows still work, because the
 * difference is carried by the message that is sent rather than by the response — and only
 * the person holding the mailbox sees that.
 *
 * <p>This costs something real: someone who mistypes their address on registration gets a
 * success response and no email. That is the trade, and the alternative is an endpoint that
 * answers "is this person a user here" to anybody who asks.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserService users;
    private final CredentialTokens tokens;
    private final Mailer mailer;

    /**
     * Where a link in one of these messages should land: the interface, not this service.
     *
     * <p>These were the issuer URL, which is where the endpoints are, and that made both
     * links dead. {@code /auth/verify} and {@code /auth/reset} take a JSON body over POST,
     * so a browser following one of them arrives with a GET and is told the method is not
     * allowed. A verification link has to open a page that can read the token out of the
     * query and submit it, and only the interface has pages.
     */
    private final String interfaceUrl;

    RegistrationService(UserService users, CredentialTokens tokens, Mailer mailer,
                        @Value("${rover.identity.interface-url}") String interfaceUrl) {
        this.users = users;
        this.tokens = tokens;
        this.mailer = mailer;
        this.interfaceUrl = interfaceUrl;
    }

    /**
     * Creates an account and sends a verification link, or does neither.
     *
     * <p>An address that is already registered produces no second account and no message
     * saying so. What it does produce is a message to the address itself, telling whoever
     * holds it that someone tried — which is useful to them and useless to whoever tried.
     */
    @Transactional
    public void register(String email, String password, String displayName) {
        UUID id = users.register(email, password, displayName).orElse(null);
        if (id == null) {
            mailer.send(email, "Someone tried to register your address", """
                    An account already exists for this address, so nothing was created.

                    If this was you, sign in instead, or reset your password if you have
                    forgotten it. If it was not, no action is needed: whoever tried was not
                    told that this address is registered.""");
            log.debug("Registration attempted for an address that already has an account");
            return;
        }

        mailer.send(email, "Confirm your address", """
                Confirm your address to finish setting up your account:

                %s/account/verify?token=%s

                The link is good for a day and can be used once. If you did not create an
                account, you can ignore this message.""".formatted(interfaceUrl, tokens.issue(
                        id, CredentialTokens.Purpose.EMAIL_VERIFICATION)));
    }

    /**
     * Marks an address proven, which is what lets the account sign in.
     *
     * @return whether the token was one. A caller reports the same thing either way; the
     *         value is returned so the interface can say "this link has already been used"
     *         rather than showing a success page for a link that did nothing.
     */
    @Transactional
    public boolean verify(String token) {
        return tokens.redeem(token, CredentialTokens.Purpose.EMAIL_VERIFICATION)
                .map(id -> {
                    users.markEmailVerified(id);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Sends a reset link, if there is an account to send one for.
     *
     * <p>Deliberately returns nothing. An unknown address, a disabled account and a locked
     * one all take this path silently, so the response is identical in every case.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(email).ifPresentOrElse(
                user -> mailer.send(user.email(), "Reset your password", """
                        Someone asked to reset the password for this account. To choose a
                        new one:

                        %s/account/reset?token=%s

                        The link is good for an hour and can be used once. If this was not
                        you, nothing has changed and you can ignore this message.""".formatted(
                                interfaceUrl, tokens.issue(user.id(), CredentialTokens.Purpose.PASSWORD_RESET))),
                () -> log.debug("Password reset requested for an address with no usable account"));
    }

    /**
     * Sets a new password if the token is good.
     *
     * <p>A successful reset clears the lockout. Someone who forgot their password is the
     * likeliest source of the failed attempts that caused it, and leaving the lock in place
     * would mean recovering the password and still being unable to use it.
     *
     * @return whether the token was one
     */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        return tokens.redeem(token, CredentialTokens.Purpose.PASSWORD_RESET)
                .map(id -> {
                    users.changePassword(id, newPassword);
                    users.recordSuccessfulSignIn(id);
                    return true;
                })
                .orElse(false);
    }
}
