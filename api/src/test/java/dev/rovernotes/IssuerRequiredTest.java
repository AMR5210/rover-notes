package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The issuer has no fallback, and that is the point.
 *
 * <p>{@code rover.identity.issuer} is the address tokens claim to come from and the one
 * clients validate against. It used to fall back to {@code http://localhost:8080}, so a
 * service deployed without setting it would start, report itself healthy, and advertise
 * localhost in its discovery documents and in every token it signed. The failure surfaces
 * at the client, which rejects tokens from an issuer it cannot reach — nowhere near the
 * mistake, and looking like somebody else's fault.
 *
 * <p>Asserted against the configuration file rather than by booting a context without one.
 * The deployed profile needs mail and a database configured before it reaches the issuer,
 * so a boot test fails on whichever bean comes first and passes for the wrong reason —
 * which is what the first attempt at this test did, twice. What is worth pinning is the
 * decision: this property must never regain a default.
 */
class IssuerRequiredTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yml");
    private static final Path LOCAL = Path.of("src/main/resources/application-local.yml");

    /** A placeholder, capturing whatever follows the colon that would make it optional. */
    private static final Pattern ISSUER =
            Pattern.compile("^\\s*issuer:\\s*\\$\\{([A-Z_]+)(:([^}]*))?}\\s*$", Pattern.MULTILINE);

    @Test
    void theDeployedProfileSuppliesNoFallbackForTheIssuer() throws IOException {
        Matcher matcher = ISSUER.matcher(Files.readString(CONFIG));

        assertThat(matcher.find()).as("application.yml declares the issuer").isTrue();
        assertThat(matcher.group(1)).isEqualTo("ROVER_ISSUER_URI");
        assertThat(matcher.group(3))
                .as("a default here lets a deployed service advertise localhost as its issuer")
                .isNull();
    }

    @Test
    void theLocalProfileSuppliesOneSoDevelopmentIsUnaffected() throws IOException {
        // The other half of the trade. Removing the default without this would mean every
        // local run needed an environment variable to start.
        assertThat(Files.readString(LOCAL))
                .as("the local profile states its own issuer")
                .containsPattern("issuer:\\s*http://localhost:8080");
    }
}
