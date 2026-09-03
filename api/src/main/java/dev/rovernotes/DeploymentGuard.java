package dev.rovernotes;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the development profile has been left on somewhere real.
 *
 * <p>The {@code local} profile permits every request without authentication and attributes
 * all data to one fixed owner. That is the right shape for a laptop and the worst possible
 * shape anywhere else, and until now nothing enforced the difference — the file said "this
 * profile must never be active in a deployed environment" and a stray environment variable
 * was enough to ignore it.
 *
 * <h2>What is checked, and why this and not something else</h2>
 *
 * <p>Nothing inside the process knows whether it is deployed. What it does know is the
 * address it issues tokens from, and that address has to be the one callers actually reach
 * the service at — a deployment configures it because tokens are useless otherwise. So a
 * {@code local} profile beside a non-loopback issuer is the combination that means somebody
 * configured this for a real deployment and left the development profile switched on.
 *
 * <p>The converse case — deployed without an issuer at all — is handled by the property
 * having no default, so the context fails to build rather than being caught here.
 *
 * <p>This deliberately does not check for a remote database or a public storage endpoint.
 * Pointing a laptop at a shared development database is ordinary, and a guard that fired
 * on it would be turned off rather than heeded.
 */
@Component
@Profile("local")
class DeploymentGuard {

    private static final Logger log = LoggerFactory.getLogger(DeploymentGuard.class);

    /** Hosts that can only mean this machine. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    DeploymentGuard(@Value("${rover.identity.issuer}") String issuer) {
        String host = hostOf(issuer);

        if (!LOOPBACK.contains(host)) {
            throw new IllegalStateException("""
                    The 'local' profile is active but the issuer is %s, which is not this \
                    machine. That profile permits every request without authentication and \
                    attributes all data to a single fixed owner, so it must not run \
                    anywhere reachable. Unset SPRING_PROFILES_ACTIVE, or set \
                    ROVER_ISSUER_URI back to a loopback address if this really is a \
                    development machine.""".formatted(issuer));
        }

        // Said once, loudly, because the profile's whole purpose is to remove the
        // protections whose absence is otherwise invisible from the outside.
        log.warn("Running with the 'local' profile: authentication is disabled, request "
                + "limits are off, and every request is attributed to the development owner");
    }

    private static String hostOf(String issuer) {
        try {
            String host = new URI(issuer).getHost();
            return host == null ? issuer : host;
        } catch (URISyntaxException e) {
            // An unparseable issuer is a configuration error either way, and the failure
            // belongs to whatever reads it as a URI rather than to this check.
            return issuer;
        }
    }
}
