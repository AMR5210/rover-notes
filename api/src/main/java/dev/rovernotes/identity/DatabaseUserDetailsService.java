package dev.rovernotes.identity;

import java.time.Instant;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Looks up an account by the address someone typed into a sign-in form.
 *
 * <p>An address with no account, a disabled account, an unverified one and a locked one
 * all raise the same exception, because {@link UserService#findByEmail} does not
 * distinguish them. Spring Security then reports a single failure for all of them, so the
 * form cannot be used to find out which addresses are registered.
 *
 * <p>A password check still runs when no account was found. Returning early would make an
 * unknown address measurably faster to reject than a known one with a wrong password,
 * which is the same disclosure by another route.
 *
 * <p>The principal is Spring Security's own {@link User} rather than a type of this
 * project's, and the reason is where it ends up. {@code JdbcOAuth2AuthorizationService}
 * serialises the authenticated principal into {@code oauth2_authorization.attributes} to
 * carry it across the code exchange. {@code User} implements {@code CredentialsContainer},
 * so its password is erased after authentication and what gets written is a name and some
 * authorities. A record would not be erased, and its Argon2 hash would be copied into a
 * second table — found by running the grant, which is the only thing that exercises this.
 *
 * <p>The username is the account id, not the address. That value becomes
 * {@code principal_name} on the authorization row and the {@code sub} claim of every token
 * issued, so making it the id means the subject of a token, the row recording the session,
 * and {@code owner_id} on the data are all the same identifier.
 */
@Service
class DatabaseUserDetailsService implements UserDetailsService {

    /**
     * A valid Argon2id encoding of a value no password produces, hashed against on the
     * miss path so that the work done is the same either way. It is never matched.
     */
    private static final String ABSENT_ACCOUNT_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$"
                    + "RdescudvJCsgt3ub+b+dWRWJTmaaJObG";

    private final UserService users;
    private final PasswordEncoder passwords;

    DatabaseUserDetailsService(UserService users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        Instant now = Instant.now();
        return users.findByEmail(email)
                .<UserDetails>map(user -> User.withUsername(user.id().toString())
                        .password(user.passwordHash())
                        .authorities(users.authorities(user.id()).stream()
                                .map(SimpleGrantedAuthority::new).toList())
                        .accountLocked(user.lockedAt(now))
                        .disabled(user.disabled() || !user.verified())
                        .build())
                .orElseGet(() -> {
                    passwords.matches("", "{argon2}" + ABSENT_ACCOUNT_HASH);
                    throw new UsernameNotFoundException("no account for the supplied address");
                });
    }
}
