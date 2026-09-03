package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * An agent that registered itself, all the way to calling a tool.
 *
 * <p>{@code McpDiscoveryTest} shows a client can find its way from a refusal to the
 * registration endpoint. This is the other half: that following the rest of the way
 * actually produces a token the tool surface accepts. Until this ran, no token had ever
 * been issued to a self-registered client, so the approval step that separates one from the
 * web interface had been asserted as required and never once satisfied.
 *
 * <p>The consent screen is the part that only appears here. The web client skips it and its
 * grant test therefore never rendered one; a client that registered itself must pass
 * through it, and a consent page that did not work would have made every agent flow stop at
 * a screen nobody could get past.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingMailer.Config.class)
@TestPropertySource(properties = {
        "rover.identity.key-encryption-key=YWdlbnQtdG9rZW4tdGVzdC1rZXktMzItYnl0ZXMhISE=",
        // No default any more: a deployed run states the address it issues from.
        "rover.identity.issuer=http://localhost:8080",
        "spring.mail.host=localhost"})
class McpAgentTokenTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final String REDIRECT_URI = "http://127.0.0.1:9000/callback";
    private static final String PASSWORD = "a-sufficiently-long-password";

    @LocalServerPort
    int port;

    @Autowired
    RegistrationService registration;

    @Autowired
    RecordingMailer mail;

    private final ObjectMapper json = new ObjectMapper();

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    /** The scopes the consent screen actually offers, which is what a person ticks. */
    private static final Pattern OFFERED_SCOPE =
            Pattern.compile("type=\"checkbox\"[^>]*name=\"scope\"[^>]*value=\"([^\"]+)\"");

    private HttpClient agent() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Registers a client the way an agent does: open endpoint, no credential presented. */
    private String registerClient(HttpClient http) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/register")))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"client_name":"An agent","redirect_uris":["%s"],
                                 "grant_types":["authorization_code"]}""".formatted(REDIRECT_URI)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).get("client_id").asText();
    }

    private String verifiedAccount() {
        String email = "agent-" + UUID.randomUUID() + "@example.com";
        registration.register(email, PASSWORD, "Agent Test");
        assertThat(registration.verify(mail.lastTokenFor(email).orElseThrow())).isTrue();
        return email;
    }

    private void signIn(HttpClient http, String email) throws Exception {
        HttpResponse<String> form = http.send(
                HttpRequest.newBuilder(URI.create(url("/login"))).build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = CSRF.matcher(form.body());
        assertThat(csrf.find()).isTrue();

        HttpResponse<String> posted = http.send(
                HttpRequest.newBuilder(URI.create(url("/login")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=" + enc(email) + "&password=" + enc(PASSWORD)
                                        + "&_csrf=" + enc(csrf.group(1))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(posted.headers().firstValue("location").orElseThrow()).doesNotContain("error");
    }

    private HttpResponse<String> authorize(HttpClient http, String clientId, String challenge)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorize"
                        + "?response_type=code&client_id=" + enc(clientId)
                        + "&redirect_uri=" + enc(REDIRECT_URI)
                        + "&scope=" + enc("openid profile")
                        + "&code_challenge=" + challenge
                        + "&code_challenge_method=S256"))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void asksForApprovalRatherThanIssuingACodeStraightAway() throws Exception {
        HttpClient http = agent();
        String clientId = registerClient(http);
        signIn(http, verifiedAccount());

        HttpResponse<String> response = authorize(http, clientId, challengeFor(randomVerifier()));

        // The web interface gets a 302 with a code at this point. A self-registered client
        // gets a screen, which is the whole difference between the two.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("consent_form");
        // The screen offers what it is asking for. openid is absent because OIDC treats it
        // as identifying rather than as a permission, so there is nothing to decide about.
        assertThat(offeredScopes(response.body())).contains("profile");
    }

    @Test
    void reachesTheToolSurfaceAfterThePersonApproves() throws Exception {
        HttpClient http = agent();
        String clientId = registerClient(http);
        signIn(http, verifiedAccount());

        String verifier = randomVerifier();
        HttpResponse<String> consent = authorize(http, clientId, challengeFor(verifier));
        assertThat(consent.statusCode()).as("expected a consent screen").isEqualTo(200);

        HttpResponse<String> approved = approve(http, consent.body(), clientId);

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(302);
        String location = approved.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith(REDIRECT_URI).contains("code=");

        HttpResponse<String> token = http.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/token")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code"
                                        + "&code=" + enc(queryParam(location, "code"))
                                        + "&redirect_uri=" + enc(REDIRECT_URI)
                                        + "&client_id=" + enc(clientId)
                                        + "&code_verifier=" + enc(verifier)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        String accessToken = json.readTree(token.body()).get("access_token").asText();

        // The point of the whole chain. A plain client, no cookies, carrying only what the
        // flow produced.
        HttpClient plain = HttpClient.newHttpClient();
        HttpResponse<String> called = plain.send(
                HttpRequest.newBuilder(URI.create(url("/mcp")))
                        .header("content-type", "application/json")
                        .header("accept", "application/json, text/event-stream")
                        .header("authorization", "Bearer " + accessToken)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                                 "protocolVersion":"2025-06-18","capabilities":{},
                                 "clientInfo":{"name":"acceptance","version":"1"}}}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Whatever the protocol makes of the body, the token was accepted: 401 is the
        // answer without one, and that is the property this whole chain exists to change.
        assertThat(called.statusCode()).as("BODY:" + called.body()).isEqualTo(200);

        HttpResponse<String> anonymous = plain.send(
                HttpRequest.newBuilder(URI.create(url("/mcp")))
                        .header("content-type", "application/json")
                        .header("accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void theTokenItReceivesIsForThePersonWhoApprovedIt() throws Exception {
        HttpClient http = agent();
        String clientId = registerClient(http);
        String email = verifiedAccount();
        signIn(http, email);

        String verifier = randomVerifier();
        HttpResponse<String> consent = authorize(http, clientId, challengeFor(verifier));
        HttpResponse<String> approved = approve(http, consent.body(), clientId);

        HttpResponse<String> token = http.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/token")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code"
                                        + "&code=" + enc(queryParam(
                                                approved.headers().firstValue("location").orElseThrow(),
                                                "code"))
                                        + "&redirect_uri=" + enc(REDIRECT_URI)
                                        + "&client_id=" + enc(clientId)
                                        + "&code_verifier=" + enc(verifier)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // An agent acts for a person, so its token has to say which one. The subject is
        // that person's account id, and owner_id on everything the tools reach is the same
        // value — which is what stops an agent seeing anybody else's notes.
        JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(
                json.readTree(token.body()).get("access_token").asText().split("\\.")[1]),
                StandardCharsets.UTF_8));
        assertThat(claims.get("email").asText()).isEqualTo(email);
        assertThat(UUID.fromString(claims.get("sub").asText())).isNotNull();
    }

    /**
     * Submits the consent form as the rendered page would.
     *
     * <p>No CSRF token, and none is rendered: the authorization server's chain excludes its
     * own protocol endpoints from CSRF, so the page has nothing to carry. Sending one would
     * be describing a form that does not exist.
     */
    private HttpResponse<String> approve(HttpClient http, String consentPage, String clientId)
            throws Exception {
        StringBuilder form = new StringBuilder("client_id=").append(enc(clientId))
                .append("&state=").append(enc(hidden(consentPage, "state")));
        for (String scope : offeredScopes(consentPage)) {
            form.append("&scope=").append(enc(scope));
        }
        return http.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorize")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static java.util.List<String> offeredScopes(String consentPage) {
        Matcher matcher = OFFERED_SCOPE.matcher(consentPage);
        java.util.List<String> scopes = new java.util.ArrayList<>();
        while (matcher.find()) {
            scopes.add(matcher.group(1));
        }
        return scopes;
    }

    // ---------------------------------------------------------------- helpers

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hidden(String html, String name) {
        Matcher matcher = Pattern.compile(
                "name=\"" + name + "\"[^>]*value=\"([^\"]*)\"").matcher(html);
        assertThat(matcher.find()).as("hidden field %s", name).isTrue();
        return matcher.group(1);
    }

    private static String randomVerifier() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String challengeFor(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String queryParam(String url, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(url);
        assertThat(matcher.find()).as("%s in %s", name, url).isTrue();
        return java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
