package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The deployed authorisation rules, exercised rather than described.
 *
 * <p>Every other integration test runs under the {@code local} profile, which permits
 * every request so the stack can be driven without real credentials. That leaves the
 * chain that actually protects a deployed environment covered by nothing: a mistake in a
 * matcher, or an endpoint mounted outside {@code /api}, would pass the whole suite. This
 * test boots without that profile and asks what an unauthenticated caller can reach.
 *
 * <p>The key-encryption key and the mail host are supplied because this service refuses to
 * start without either once it is issuing its own tokens and sending its own mail. No token
 * is presented here and no message is sent: what is under test is what happens to a request
 * carrying no credentials at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "rover.identity.key-encryption-key=dGVzdC1vbmx5LWtleS1mb3Itc2VjdXJpdHktdGVzdHM=",
        // No default any more: a deployed run states the address it issues from.
        "rover.identity.issuer=http://localhost:8080",
        // Outside the local profile mail is sent rather than logged, and the sender is
        // required at startup: a deployment that cannot deliver a reset link should say so
        // on boot rather than at the first person who needs one. Nothing is sent here.
        "spring.mail.host=localhost"})
class SecurityConfigTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    /**
     * The JDK client rather than a Spring one, because the assertion is about a status
     * code: a test client that follows redirects or unwraps an error body would report
     * something other than what the chain returned.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private int status(String method, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("content-type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("request to " + path + " failed", cause);
        }
    }

    /**
     * Spend is the most sensitive thing the API reports, and it was the most recent
     * endpoint added. An endpoint is protected because of where it is mounted, so a new
     * one outside {@code /api} would be reachable without anyone changing this chain.
     */
    @Test
    void refusesAnUnauthenticatedRequestForSpend() {
        assertThat(status("GET", "/api/usage", null)).isEqualTo(401);
    }

    @Test
    void refusesUnauthenticatedRequestsAcrossTheApi() {
        for (String path : new String[] {"/api/notes", "/api/search?q=anything", "/api/usage"}) {
            assertThat(status("GET", path, null)).as("GET %s", path).isEqualTo(401);
        }
    }

    @Test
    void refusesAnUnauthenticatedWrite() {
        // CSRF is disabled on this chain, so a 403 here would mean the write was
        // authorised and then blocked for a different reason. Only 401 shows the request
        // was refused for having no identity.
        assertThat(status("POST", "/api/notes", "{\"title\":\"t\",\"content\":\"c\"}"))
                .isEqualTo(401);
    }

    /**
     * The tool surface is not under {@code /api}, so it needs its own matcher. Without one
     * it falls to {@code denyAll} and is unreachable in every deployed environment, which
     * is a failure that looks like an outage rather than like a security setting.
     */
    @Test
    void refusesAnUnauthenticatedToolCallRatherThanDenyingItOutright() {
        assertThat(status("POST", "/mcp", "{}")).isEqualTo(401);
    }

    /**
     * Health has to answer an unauthenticated prober, or an orchestrator cannot tell a
     * started container from a broken one.
     */
    @Test
    void allowsTheHealthProbeThroughUnauthenticated() {
        assertThat(status("GET", "/actuator/health", null)).isEqualTo(200);
    }

    /**
     * Health is the only actuator endpoint that is public. The rest report configuration,
     * beans and metrics, which is reconnaissance rather than liveness.
     */
    @Test
    void refusesTheRestOfActuator() {
        assertThat(status("GET", "/actuator/metrics", null)).isEqualTo(401);
    }

    /**
     * Anything not matched above is denied rather than permitted, so adding a controller
     * outside the matched prefixes fails closed.
     *
     * <p>The status is 401 rather than 403. A caller carrying no token is anonymous, and
     * Spring Security answers a denial for an anonymous request through the authentication
     * entry point, which asks for credentials; 403 is what an authenticated caller would
     * get. Either way nothing is served, which is the property under test.
     */
    @Test
    void deniesAnythingNotExplicitlyMatched() {
        assertThat(status("GET", "/some/unmatched/path", null)).isEqualTo(401);
    }
}
