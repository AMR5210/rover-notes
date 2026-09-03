package dev.rovernotes.identity;

import jakarta.servlet.DispatcherType;

import dev.rovernotes.RateLimitFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The pages and endpoints a caller reaches before they have a token.
 *
 * <p>Its own chain, between the authorization server's and the application's. The three
 * want different things and combining any two of them would mean giving something up:
 *
 * <ul>
 *   <li>The authorization server holds a session across the code exchange.</li>
 *   <li>This chain holds a session too, because a form login has nowhere else to put the
 *       authentication that the authorization endpoint then reads.</li>
 *   <li>The application's chain is stateless and bearer-only, which is what keeps
 *       {@code /api/**} free of cookies and therefore free of CSRF concerns.</li>
 * </ul>
 *
 * <p>Active in every profile, including {@code local}. The authorization code flow is the
 * path a token is obtained through, and a flow that only works where authentication is
 * switched off would be untested exactly where it matters.
 */
@Configuration
class SignInConfig {

    /**
     * Ordered after the authorization server and before the application.
     *
     * <p>The gap is deliberate: the protocol endpoints claim their own paths first, and
     * whatever this does not match falls through to the chain that ends in {@code denyAll}.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    SecurityFilterChain signInChain(HttpSecurity http,
                                    RateLimitFilter rateLimit) throws Exception {
        return http
                .securityMatcher("/auth/**", "/login", "/logout")
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Creating an account and recovering one are the only things in the
                        // system a caller with no identity has to be able to do.
                        .requestMatchers("/auth/**").permitAll()
                        .anyRequest().permitAll())
                // CSRF stays on for the login form, which is cookie-authenticated and is
                // exactly what the protection is for. It is off for /auth/**, which carries
                // no ambient authority: a forged cross-site request there achieves nothing
                // that calling the endpoint directly would not, and requiring a token first
                // would mean fetching one before anyone can register.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/auth/**"))
                // This project's own pages, served by SignInPage. Naming a login page also
                // withdraws Spring's generated logout page — DefaultLoginPageConfigurer
                // registers the two inside the same branch — so both are served here or
                // neither is, and the interface's sign-out is a plain navigation to
                // /logout that would otherwise reach nothing.
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(Customizer.withDefaults())
                // Counted by client address, since a caller here has no account yet. This
                // is what bounds account creation and password-reset mail; the per-account
                // lockout is a different protection and cannot see one address being tried
                // against many.
                .addFilterAfter(rateLimit, AuthorizationFilter.class)
                .build();
    }
}
