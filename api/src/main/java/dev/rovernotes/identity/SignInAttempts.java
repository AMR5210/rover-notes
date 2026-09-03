package dev.rovernotes.identity;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Counts sign-in failures so that guessing a password online becomes impractical.
 *
 * <p>Driven by Spring Security's authentication events rather than by a filter, because
 * every authentication path publishes them: a form post, a token exchange, and anything
 * added later are all counted without each one remembering to.
 *
 * <p>The two events carry different principals, which is why they are read differently. A
 * failure happens before an account is established, so its name is whatever address was
 * submitted. A success carries the resolved principal, whose name is the account id.
 */
@Component
class SignInAttempts {

    private final UserService users;

    SignInAttempts(UserService users) {
        this.users = users;
    }

    @EventListener
    void onFailure(AbstractAuthenticationFailureEvent event) {
        // An address with no account, or one already locked, resolves to nothing here and
        // is not counted. Neither needs to be: there is no account to protect in the first
        // case, and the second is already refusing attempts.
        users.findByEmail(String.valueOf(event.getAuthentication().getName()))
                .ifPresent(user -> users.recordFailedSignIn(user.id()));
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        try {
            users.recordSuccessfulSignIn(UUID.fromString(event.getAuthentication().getName()));
        } catch (IllegalArgumentException notAnAccountId) {
            // Some other authentication mechanism, whose principal is not an account id.
            // Nothing to clear, and guessing at a mapping would clear the wrong row.
        }
    }

    /** Kept for callers holding an id rather than a principal. */
    void clear(UUID id) {
        users.recordSuccessfulSignIn(id);
    }
}
