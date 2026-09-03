package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The path from "you may not" to "here is how", walked in order.
 *
 * <p>An agent handed only a URL for an MCP server has no configuration to fall back on. It
 * is refused, reads the {@code WWW-Authenticate} header, fetches the resource metadata it
 * names, follows that to the authorization server, reads its metadata, registers itself,
 * and starts a code flow. Every one of those steps has to be served for the next to be
 * reachable, which is why they are asserted as a chain here rather than one at a time.
 *
 * <p>Run without the {@code local} profile, because the refusal that begins the chain only
 * happens on the resource-server chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingMailer.Config.class)
@TestPropertySource(properties = {
        "rover.identity.key-encryption-key=bWNwLWRpc2NvdmVyeS10ZXN0LWtleS0zMi1ieXRlcyE=",
        // No default any more: a deployed run states the address it issues from.
        "rover.identity.issuer=http://localhost:8080",
        "spring.mail.host=localhost"})
class McpDiscoveryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void refusesAnAnonymousToolCallAndSaysWhereToLearnMore() throws Exception {
        HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(401);
        // The header is the first link in the chain. Without the resource_metadata
        // parameter an agent knows only that it was refused, not what to do about it.
        assertThat(refused.headers().firstValue("WWW-Authenticate").orElseThrow())
                .startsWith("Bearer")
                .contains("resource_metadata=");
    }

    @Test
    void servesTheResourceMetadataItsRefusalPointsAt() throws Exception {
        String metadataUrl = resourceMetadataUrl();

        HttpResponse<String> metadata = get(metadataUrl);

        // A refusal advertising a document that does not exist is worse than one that says
        // nothing: it sends an agent somewhere and leaves it there.
        assertThat(metadata.statusCode()).as(metadataUrl).isEqualTo(200);
        JsonNode document = json.readTree(metadata.body());
        assertThat(document.get("resource").asText()).isNotEmpty();
    }

    @Test
    void namesTheAuthorizationServerToGetATokenFrom() throws Exception {
        JsonNode document = json.readTree(get(resourceMetadataUrl()).body());

        // The step that turns "you need a token" into somewhere to get one.
        assertThat(document.get("authorization_servers")).isNotEmpty();
        assertThat(document.get("authorization_servers").get(0).asText())
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void theNamedAuthorizationServerDescribesHowToRegisterAndObtainAToken() throws Exception {
        JsonNode metadata = json.readTree(get(base() + "/.well-known/oauth-authorization-server").body());

        // RFC 7591's endpoint, which is not the OIDC one at /connect/register. An agent
        // reads this rather than assuming either, and so does the helper below.
        assertThat(metadata.get("registration_endpoint").asText()).contains("/oauth2/register");
        assertThat(metadata.get("authorization_endpoint").asText()).contains("/oauth2/authorize");
        assertThat(metadata.get("token_endpoint").asText()).contains("/oauth2/token");
        assertThat(metadata.get("code_challenge_methods_supported").toString()).contains("S256");
    }

    @Test
    void letsAClientRegisterItselfWithoutACredential() throws Exception {
        // The point of open registration: an agent given only a URL has nothing to present.
        HttpResponse<String> registered = register("""
                {"client_name":"An agent","redirect_uris":["http://127.0.0.1:9000/callback"],
                 "grant_types":["authorization_code"]}""");

        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        JsonNode client = json.readTree(registered.body());
        assertThat(client.get("client_id").asText()).isNotEmpty();
        assertThat(client.get("redirect_uris").get(0).asText())
                .isEqualTo("http://127.0.0.1:9000/callback");
    }

    @Test
    void issuesNoSecretToSomethingThatRegisteredItself() throws Exception {
        // A secret handed out on request belongs to whoever asked, which is nobody in
        // particular. PKCE does the work a secret would have done.
        JsonNode client = json.readTree(register("""
                {"client_name":"An agent","redirect_uris":["http://127.0.0.1:9000/callback"]}""")
                .body());

        assertThat(client.has("client_secret")).isFalse();
        assertThat(client.get("token_endpoint_auth_method").asText()).isEqualTo("none");
    }

    @Test
    void refusesARegistrationWithNowhereToSendACode() throws Exception {
        // Such a client could never complete a flow, so accepting it would only produce a
        // row that fails later and further away.
        assertThat(register("""
                {"client_name":"An agent with no redirect"}""").statusCode())
                .isNotEqualTo(201);
    }

    @Test
    void refusesARegistrationThatAsksForScopes() throws Exception {
        // Spring Authorization Server's rule, not this project's, and a good one: something
        // registering itself does not get to say what it may do. Asserted because it is why
        // AgentClientRegistration assigns scopes rather than filtering requested ones, and
        // a future version that relaxed it should be noticed here.
        HttpResponse<String> refused = register("""
                {"client_name":"An ambitious agent",
                 "redirect_uris":["http://127.0.0.1:9000/callback"],
                 "scope":"openid profile admin everything"}""");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("invalid_scope");
    }

    @Test
    void assignsTheScopesThisServerGrantsRatherThanAnythingAsked() throws Exception {
        JsonNode client = json.readTree(register("""
                {"client_name":"An agent","redirect_uris":["http://127.0.0.1:9000/callback"]}""")
                .body());

        // Both identify the person. Neither widens what the tools reach, which is decided
        // by whose token the agent holds.
        assertThat(client.get("scope").asText()).contains("openid").contains("profile");
    }

    @Test
    void aSelfRegisteredClientCannotSkipTheSignInOrTheApproval() throws Exception {
        JsonNode client = json.readTree(register("""
                {"client_name":"An agent","redirect_uris":["http://127.0.0.1:9000/callback"],
                 "grant_types":["authorization_code","client_credentials"]}""").body());

        // client_credentials would be a token with no person behind it, which is the one
        // shape that must not be obtainable by registering for it.
        assertThat(client.get("grant_types").toString()).doesNotContain("client_credentials");
        assertThat(client.get("grant_types").toString()).contains("authorization_code");

        // And starting a flow still requires signing in, so registration on its own reaches
        // nothing at all.
        HttpResponse<String> authorize = get(base() + "/oauth2/authorize"
                + "?response_type=code&client_id=" + client.get("client_id").asText()
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A9000%2Fcallback"
                + "&scope=openid&code_challenge=abc&code_challenge_method=S256");
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(authorize.headers().firstValue("location").orElseThrow()).endsWith("/login");
    }

    /** Posted to whatever the metadata advertises, which is how an agent finds it. */
    private HttpResponse<String> register(String body) throws Exception {
        String endpoint = json.readTree(get(base() + "/.well-known/oauth-authorization-server").body())
                .get("registration_endpoint").asText()
                .replaceFirst("^http://localhost:8080", base());
        return http.send(
                HttpRequest.newBuilder(URI.create(endpoint))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Read from the refusal rather than assumed, which is how an agent finds it. */
    private String resourceMetadataUrl() throws Exception {
        HttpResponse<Void> refused = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        Matcher matcher = Pattern.compile("resource_metadata=\"([^\"]+)\"")
                .matcher(refused.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
