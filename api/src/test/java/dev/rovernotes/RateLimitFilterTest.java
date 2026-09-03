package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The limit as a caller meets it: over HTTP, with a status and a header.
 *
 * <p>{@code RateLimiterTest} covers the bucket arithmetic. What it cannot cover is any of
 * the wiring — whether the filter is in the chain at all, whether it runs on the paths
 * intended, and whether a refusal comes back as 429 with something a client can act on.
 * Each of those fails silently: a filter that is never added leaves every request served,
 * which is exactly what the system did before this existed.
 *
 * <p>The limit is switched on with a property rather than by activating the deployed
 * profile. It is off in {@code local} because the eval and load harnesses drive far more
 * than a person would from one address, and a test that had to boot the deployed chain to
 * see the filter would be testing authentication as much as the limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "rover.rate-limit.enabled=true",
        "rover.rate-limit.api-per-minute=2",
        "rover.rate-limit.auth-per-minute=2"})
class RateLimitFilterTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Every request in this class arrives from the same loopback address, and nothing here
     * authenticates, so all of them share one bucket. Clearing it is what keeps each case
     * independent of the order the cases ran in.
     */
    @BeforeEach
    void emptyTheBuckets() {
        jdbc.sql("delete from rate_limits").update();
    }

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("content-type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("request to " + path + " failed", cause);
        }
    }

    private int get(String path) {
        return send("GET", path, null).statusCode();
    }

    @Test
    void refusesTheRequestAfterTheAllowanceIsSpent() {
        assertThat(IntStream.range(0, 2).map(i -> get("/api/usage")).boxed())
                .allMatch(status -> status == 200);

        assertThat(get("/api/usage")).isEqualTo(429);
    }

    @Test
    void tellsARefusedCallerHowLongToWait() {
        IntStream.range(0, 3).forEach(i -> get("/api/usage"));

        HttpResponse<String> refused = send("GET", "/api/usage", null);

        // Both, for the same reason AskController sends both on a spend refusal: the
        // header is what a generic HTTP client honours and the body is what an interface
        // can explain to a person.
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("retry-after")).isPresent();
        assertThat(refused.body()).contains("retryAfterSeconds");
    }

    /**
     * Account creation is bounded.
     *
     * <p>Its own chain and its own bucket, and the reason the limit exists: registration is
     * open, so before this an address could be tried against the endpoint as fast as the
     * service would answer, hashing a password at Argon2's 19 MiB baseline and sending mail
     * each time. It is also the only limit here keyed on the caller's address, since a
     * caller registering has no account to key on.
     */
    @Test
    void boundsAccountCreation() {
        assertThat(IntStream.range(0, 2).map(i -> register("first" + i)).boxed())
                .allMatch(status -> status == 202);

        assertThat(register("third")).isEqualTo(429);
    }

    private int register(String local) {
        return send("POST", "/auth/register", """
                {"email":"%s@test.invalid","password":"a-long-enough-password",
                 "displayName":"Test"}
                """.formatted(local)).statusCode();
    }

    @Test
    void doesNotLetOneKindOfRequestExhaustAnother() {
        // Registering an account and reading usage are counted separately. Sharing a
        // bucket would mean a caller who had searched their allowance away could not
        // create an account, and the two limits are set to different numbers precisely
        // because the requests cost different things.
        IntStream.range(0, 3).forEach(i -> get("/api/usage"));
        assertThat(get("/api/usage")).isEqualTo(429);

        assertThat(register("still-allowed")).isEqualTo(202);
    }

    @Test
    void leavesTheHealthProbeAlone() {
        // A probe answered with 429 reports the instance unhealthy, which removes an
        // instance that is still serving everyone under their limit. The failure mode is
        // that load causes the withdrawal of the capacity handling it.
        IntStream.range(0, 10).forEach(i -> get("/api/usage"));

        assertThat(IntStream.range(0, 10).map(i -> get("/actuator/health")).boxed())
                .allMatch(status -> status == 200);
    }
}
