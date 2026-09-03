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
 * A token obtained the way the interface will obtain one, and then used.
 *
 * <p>Everything else about identity has been asserted at its edges: that a password is
 * stored correctly, that a key is held encrypted, that a hand-signed token maps to an
 * owner, that the protocol endpoints are mounted. None of that runs the flow. This does —
 * sign in, authorize, exchange the code, call the API with what comes back — which is the
 * first time the pieces are shown to fit each other rather than to their own tests.
 *
 * <p>Deliberately without the {@code local} profile. Under it every request is permitted
 * and attributed to a fixed owner, so a token would be carried and never consulted; the
 * assertion that {@code /api/notes} answers <em>this</em> account only means something on
 * the chain that actually validates a bearer token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingMailer.Config.class)
@TestPropertySource(properties = {
        "rover.identity.key-encryption-key=Z3JhbnQtdGVzdC1rZXktZXhhY3RseS0zMi1ieXRlcyE=",
        // No default any more: a deployed run states the address it issues from.
        "rover.identity.issuer=http://localhost:8080",
        "rover.identity.web-redirect-uris=http://127.0.0.1/callback",
        // Only so a JavaMailSender exists to satisfy SmtpMailer outside the local profile.
        // Nothing is sent: RecordingMailer is primary.
        "spring.mail.host=localhost"})
class AuthorizationCodeGrantTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final String CLIENT_ID = "rover-web";
    private static final String REDIRECT_URI = "http://127.0.0.1/callback";
    private static final String PASSWORD = "a-sufficiently-long-password";

    @LocalServerPort
    int port;

    @Autowired
    RegistrationService registration;

    @Autowired
    RecordingMailer mail;

    private final ObjectMapper json = new ObjectMapper();

    /** A client with a cookie jar, because the code grant carries a session across steps. */
    private HttpClient browser() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Creates an account and clicks the link, leaving it able to sign in. */
    private String verifiedAccount() {
        String email = "grant-" + UUID.randomUUID() + "@example.com";
        registration.register(email, PASSWORD, "Grant Test");
        assertThat(registration.verify(mail.lastTokenFor(email).orElseThrow())).isTrue();
        return email;
    }

    private static final Pattern CSRF =
            Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    /** Signs in through the generated form, leaving the session authenticated. */
    private void signIn(HttpClient browser, String email) throws Exception {
        HttpResponse<String> form = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(form.statusCode()).as("the sign-in form has to be reachable").isEqualTo(200);

        Matcher csrf = CSRF.matcher(form.body());
        assertThat(csrf.find()).as("the login form carries a CSRF token").isTrue();

        String body = "username=" + enc(email) + "&password=" + enc(PASSWORD)
                + "&_csrf=" + enc(csrf.group(1));
        HttpResponse<String> posted = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // A failed form login redirects back to /login?error rather than failing outright.
        assertThat(posted.statusCode()).isEqualTo(302);
        assertThat(posted.headers().firstValue("location").orElseThrow())
                .as("sign-in must not bounce back to the form")
                .doesNotContain("error");
    }

    @Test
    void signsIn_authorizes_exchangesTheCode_andUsesTheTokenOnTheApi() throws Exception {
        String email = verifiedAccount();
        HttpClient browser = browser();
        signIn(browser, email);

        // PKCE. The verifier never leaves the client until the exchange, so a code taken
        // off the redirect is not enough on its own.
        String verifier = randomVerifier();
        String challenge = challengeFor(verifier);

        HttpResponse<String> authorize = browser.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorize"
                        + "?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + enc(REDIRECT_URI)
                        + "&scope=" + enc("openid profile")
                        + "&code_challenge=" + challenge
                        + "&code_challenge_method=S256"))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(authorize.statusCode())
                .as("an authenticated caller is redirected back with a code, not asked to consent")
                .isEqualTo(302);
        String location = authorize.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith(REDIRECT_URI).contains("code=");
        String code = queryParam(location, "code");

        // The exchange. A public client sends no secret; the verifier is what proves it.
        String form = "grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&client_id=" + CLIENT_ID
                + "&code_verifier=" + enc(verifier);
        HttpResponse<String> token = browser.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/token")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        JsonNode issued = json.readTree(token.body());
        String accessToken = issued.get("access_token").asText();
        assertThat(issued.get("token_type").asText()).isEqualToIgnoringCase("Bearer");
        // No refresh token, and that is Spring Authorization Server's decision rather than
        // a gap in the configuration: OAuth2RefreshTokenGenerator returns null when the
        // client authenticated with NONE, because a public client cannot be trusted to
        // hold a long-lived credential. Keeping a session going is therefore a silent
        // re-authorization against the sign-in cookie, not a refresh — see
        assertThat(issued.has("refresh_token")).as(token.body()).isFalse();

        // The point of all of it: the token opens the API, and does so as this account.
        HttpClient plain = HttpClient.newHttpClient();
        HttpResponse<String> notes = plain.send(
                HttpRequest.newBuilder(URI.create(url("/api/notes")))
                        .header("authorization", "Bearer " + accessToken).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(notes.statusCode()).as(notes.body()).isEqualTo(200);
        assertThat(json.readTree(notes.body()).get("total").asInt()).isZero();

        // And the same request without it does not.
        HttpResponse<String> anonymous = plain.send(
                HttpRequest.newBuilder(URI.create(url("/api/notes"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void refusesTheExchangeWhenTheVerifierDoesNotMatchTheChallenge() throws Exception {
        // What PKCE is for. Without this check, an intercepted code is a token.
        String email = verifiedAccount();
        HttpClient browser = browser();
        signIn(browser, email);

        String challenge = challengeFor(randomVerifier());
        HttpResponse<String> authorize = browser.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorize"
                        + "?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + enc(REDIRECT_URI)
                        + "&scope=" + enc("openid profile")
                        + "&code_challenge=" + challenge
                        + "&code_challenge_method=S256"))).build(),
                HttpResponse.BodyHandlers.ofString());
        String code = queryParam(authorize.headers().firstValue("location").orElseThrow(), "code");

        String form = "grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&client_id=" + CLIENT_ID
                + "&code_verifier=" + enc(randomVerifier());
        HttpResponse<String> token = browser.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/token")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(token.statusCode()).isEqualTo(400);
        assertThat(token.body()).contains("invalid_grant");
    }

    @Test
    void sendsAnUnauthenticatedCallerToTheSignInFormRatherThanRefusingThem() throws Exception {
        // The authorization endpoint sits behind the catch-all denial, so without an
        // explicit entry point the grant cannot even begin.
        HttpResponse<String> response = browser().send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorize"
                        + "?response_type=code&client_id=" + CLIENT_ID
                        + "&redirect_uri=" + enc(REDIRECT_URI)
                        + "&scope=" + enc("openid profile")
                        + "&code_challenge=" + challengeFor(randomVerifier())
                        + "&code_challenge_method=S256"))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElseThrow()).endsWith("/login");
    }

    @Test
    void refusesASignInWithTheWrongPassword() throws Exception {
        String email = verifiedAccount();
        HttpClient browser = browser();

        HttpResponse<String> form = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login"))).build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = CSRF.matcher(form.body());
        assertThat(csrf.find()).isTrue();

        HttpResponse<String> posted = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=" + enc(email) + "&password=" + enc("not-the-password")
                                        + "&_csrf=" + enc(csrf.group(1))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(posted.headers().firstValue("location").orElseThrow()).contains("error");
    }

    @Test
    void refusesASignInForAnAccountWhoseAddressIsUnproven() throws Exception {
        // Registered but never clicked the link. The account exists and still cannot be
        // used, which is the only thing that makes a verification link worth sending.
        String email = "unverified-" + UUID.randomUUID() + "@example.com";
        registration.register(email, PASSWORD, "Unverified");

        HttpClient browser = browser();
        HttpResponse<String> form = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login"))).build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = CSRF.matcher(form.body());
        assertThat(csrf.find()).isTrue();

        HttpResponse<String> posted = browser.send(
                HttpRequest.newBuilder(URI.create(url("/login")))
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=" + enc(email) + "&password=" + enc(PASSWORD)
                                        + "&_csrf=" + enc(csrf.group(1))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(posted.headers().firstValue("location").orElseThrow()).contains("error");
    }

    @Test
    void letsAnUnauthenticatedCallerRegisterButNothingElse() throws Exception {
        HttpClient plain = HttpClient.newHttpClient();

        HttpResponse<String> registered = plain.send(
                HttpRequest.newBuilder(URI.create(url("/auth/register")))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"open-" + UUID.randomUUID()
                                        + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 202 rather than 201: whether an account was created is the fact this must not
        // report. /auth/** is the only opening in a chain that otherwise denies everything.
        assertThat(registered.statusCode()).isEqualTo(202);
        assertThat(plain.send(
                HttpRequest.newBuilder(URI.create(url("/api/notes"))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- helpers

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
