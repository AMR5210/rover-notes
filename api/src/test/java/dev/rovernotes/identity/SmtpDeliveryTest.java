package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The credential messages, sent over SMTP to a server that actually receives them.
 *
 * <p>Every other test of these flows runs against {@code RecordingMailer}, which is a list.
 * That covers what the messages say and nothing about whether they can be sent: the profile
 * that selects {@link SmtpMailer} is the one no test activated, so the delivery half of
 * registration and password reset was reasoned about rather than run. A misconfigured
 * sender, a message the server rejects, or a {@code From} the service never sets would all
 * have passed the suite and failed at the first person who needed a reset link.
 *
 * <p>Mailpit is a real SMTP server with an HTTP API for reading what arrived, so the
 * assertions here are on a message that crossed a socket. What it does not cover is
 * delivery across the internet — a provider that greylists, a domain without an SPF
 * record, a message scored as spam. That is a different gap and needs an account rather
 * than a container.
 *
 * <p>No {@code local} profile, which is the point: that profile selects the mailer that
 * writes to the log.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "rover.identity.key-encryption-key=dGVzdC1vbmx5LWtleS1mb3Itc2VjdXJpdHktdGVzdHM=",
        // No default any more: a deployed run states the address it issues from.
        "rover.identity.issuer=http://localhost:8080",
        "rover.identity.mail-from=no-reply@rover.test",
        "rover.identity.interface-url=https://notes.rover.test"})
class SmtpDeliveryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /**
     * Pinned, for the reason the compose file gives for pinning every other image: a
     * floating tag makes the version under test unknowable and moves without a commit.
     */
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.0")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        // Setting the host is also what makes Boot create the JavaMailSender at all, and
        // therefore what lets SmtpMailer be constructed.
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    RegistrationService registration;

    @Autowired
    UserService users;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearAccountsAndInbox() {
        jdbc.sql("delete from users where id <> '00000000-0000-0000-0000-000000000001'").update();
        send("DELETE", "/api/v1/messages");
    }

    private String mailpit(String path) {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path;
    }

    private String send(String method, String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(mailpit(path)))
                            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("Mailpit request to " + path + " failed", cause);
        }
    }

    private JsonNode inbox() {
        try {
            return JSON.readTree(send("GET", "/api/v1/messages"));
        } catch (IOException cause) {
            throw new IllegalStateException("could not read the inbox", cause);
        }
    }

    /** The one message that has arrived, once one has. */
    private JsonNode delivered() {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(inbox().path("total").asInt()).isEqualTo(1));

        JsonNode summary = inbox().path("messages").get(0);
        try {
            return JSON.readTree(send("GET", "/api/v1/message/" + summary.path("ID").asText()));
        } catch (IOException cause) {
            throw new IllegalStateException("could not read the message", cause);
        }
    }

    @Test
    void sendsAVerificationMessageOverSmtp() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");

        JsonNode message = delivered();

        assertThat(message.path("To").get(0).path("Address").asText()).isEqualTo("ada@example.com");
        assertThat(message.path("Subject").asText()).isEqualTo("Confirm your address");
        // The address the service is configured to send from, which nothing outside this
        // test has ever exercised: SimpleMailMessage takes it from rover.identity.mail-from
        // and a server may refuse a message whose sender it does not recognise.
        assertThat(message.path("From").path("Address").asText()).isEqualTo("no-reply@rover.test");
    }

    @Test
    void theLinkInTheMessageIsOneABrowserCanFollow() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");

        String body = delivered().path("Text").asText();

        // The interface's address, not the issuer's. Both links pointed at the API's POST
        // endpoints until the pages existed, which made every message ever sent a dead end.
        assertThat(body).contains("https://notes.rover.test/account/verify?token=");
    }

    @Test
    void sendsAResetMessageToAnAccountThatExists() {
        registration.register("ada@example.com", "a-sufficiently-long-password", "Ada");
        registration.verify(tokenFrom(delivered().path("Text").asText()));
        send("DELETE", "/api/v1/messages");

        registration.requestPasswordReset("ada@example.com");

        JsonNode message = delivered();
        assertThat(message.path("Subject").asText()).isEqualTo("Reset your password");
        assertThat(message.path("Text").asText())
                .contains("https://notes.rover.test/account/reset?token=");
    }

    @Test
    void sendsNothingForAnAddressWithNoAccount() {
        registration.requestPasswordReset("nobody@example.com");

        // Deliberately silent. The response is identical either way, so the only thing that
        // could give the difference away is a message — and there is none to intercept.
        assertThat(inbox().path("total").asInt()).isZero();
    }

    private static String tokenFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("token=([\\w-]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
