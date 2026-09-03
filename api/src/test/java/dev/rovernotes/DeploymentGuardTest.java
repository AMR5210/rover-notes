package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The check that keeps the development profile off anything reachable.
 *
 * <p>The {@code local} profile permits every request without authentication and attributes
 * all data to one fixed owner. Its own file has always said it must never run in a deployed
 * environment, and until this existed nothing enforced that — a leftover
 * {@code SPRING_PROFILES_ACTIVE} was enough to ignore it, and the result is a service that
 * looks like it is working and has no access control at all.
 *
 * <p>Driven directly rather than through a context, because what is being tested is the
 * decision. Whether the bean is created under the right profile is a separate question and
 * one the rest of the suite answers by running under it.
 */
class DeploymentGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080",
            "http://127.0.0.1:9443",
            "https://localhost:8443",
            "http://[::1]:8080",
    })
    void aLoopbackIssuerIsADevelopmentMachine(String issuer) {
        assertThatCode(() -> new DeploymentGuard(issuer)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://rover.example.com",
            "https://api.rover.internal",
            "http://10.0.1.20:8080",
            "https://rover-notes.fly.dev",
    })
    void anIssuerThatIsNotThisMachineRefusesToStart(String issuer) {
        // The combination that means somebody configured this for a real deployment and
        // left the development profile switched on. Tokens are useless unless the issuer
        // is the address callers reach, so a configured one is the signal that this is
        // meant to be reachable.
        assertThatThrownBy(() -> new DeploymentGuard(issuer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'local' profile is active")
                .hasMessageContaining(issuer);
    }

    @Test
    void theMessageSaysWhatToDoAboutIt() {
        // A refusal at boot with no remedy in it costs somebody the time to find this
        // class. Both ways out are named, because which one is right depends on whether
        // the profile or the issuer is the mistake.
        assertThatThrownBy(() -> new DeploymentGuard("https://rover.example.com"))
                .satisfies(e -> {
                    assertThat(e).hasMessageContaining("SPRING_PROFILES_ACTIVE");
                    assertThat(e).hasMessageContaining("ROVER_ISSUER_URI");
                });
    }

    @Test
    void theMessageSaysWhatTheProfileActuallyDoes() {
        // Naming the consequence rather than the rule. "Wrong profile" is not obviously
        // urgent; "permits every request without authentication" is.
        assertThatThrownBy(() -> new DeploymentGuard("https://rover.example.com"))
                .hasMessageContaining("without authentication");
    }

    @Test
    void anUnparseableIssuerIsLeftToWhateverReadsItAsAUri() {
        // Not this check's failure to report. It refuses rather than passing it through,
        // because a value that is not a URI is certainly not a loopback address.
        assertThatThrownBy(() -> new DeploymentGuard("not a uri at all"))
                .isInstanceOf(IllegalStateException.class);
    }
}
