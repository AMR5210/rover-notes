package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The authorization server as it appears over HTTP.
 *
 * <p>What is checked here is the wiring rather than a full grant: that the protocol
 * endpoints are mounted, that they are reachable without a token, and that the metadata
 * they publish describes this service. A client obtaining a token is the next piece of
 * work and needs a registered client and a sign-in page.
 *
 * <p>Reachability is the part worth a test on its own. The endpoints sit ahead of the
 * application's own chain, whose catch-all is {@code denyAll}, so an ordering mistake
 * would leave the token endpoint refusing everyone in exactly the environments where it
 * matters — and would do so without any other test noticing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = "rover.identity.issuer=http://127.0.0.1:9443")
class AuthorizationServerTest {

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

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void publishesADiscoveryDocumentDescribingThisIssuer() throws Exception {
        HttpResponse<String> response = get("/.well-known/openid-configuration");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode document = json.readTree(response.body());
        assertThat(document.get("issuer").asText()).isEqualTo("http://127.0.0.1:9443");
        assertThat(document.get("token_endpoint").asText()).contains("/oauth2/token");
        assertThat(document.get("jwks_uri").asText()).contains("/oauth2/jwks");
    }

    @Test
    void advertisesTheAuthorizationCodeGrantAndPkce() throws Exception {
        JsonNode document = json.readTree(get("/.well-known/openid-configuration").body());

        assertThat(document.get("grant_types_supported").toString()).contains("authorization_code");
        // PKCE is what makes the code grant safe for a client that cannot keep a secret,
        // which is both the web interface and an agent registering itself.
        assertThat(document.get("code_challenge_methods_supported").toString()).contains("S256");
    }

    /**
     * OIDC registration stays closed, even though the OAuth2 one is open.
     *
     * <p>The two are different endpoints with different documents. RFC 7591's
     * {@code /oauth2/register} is open, because an agent given only an MCP URL has no
     * credential to present — see {@code McpDiscoveryTest}. OIDC's
     * {@code /connect/register} keeps its default guard and is absent from this document,
     * so opening it would be a separate decision rather than something that came along
     * with the other.
     *
     * <p>Asserted rather than left unstated so that opening it is a deliberate change with
     * a failing test attached, instead of something that arrives with an upgrade.
     */
    @Test
    void doesNotAdvertiseOidcClientRegistration() throws Exception {
        JsonNode document = json.readTree(get("/.well-known/openid-configuration").body());

        assertThat(document.has("registration_endpoint")).isFalse();
    }

    @Test
    void servesAJwkSetHoldingOnlyPublicKeyMaterial() throws Exception {
        HttpResponse<String> response = get("/oauth2/jwks");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode keys = json.readTree(response.body()).get("keys");
        assertThat(keys).isNotEmpty();
        assertThat(keys.get(0).get("kty").asText()).isEqualTo("RSA");
        assertThat(keys.get(0).has("kid")).isTrue();
        // The private exponent must never appear here. This endpoint is public by design.
        assertThat(response.body()).doesNotContain("\"d\"");
    }

    @Test
    void reachesTheProtocolEndpointsWithoutATokenDespiteTheCatchAllDenial() throws Exception {
        // The application's own chain ends in denyAll. If this chain were ordered after
        // it, both of these would be refused rather than answered.
        assertThat(get("/.well-known/openid-configuration").statusCode()).isEqualTo(200);
        assertThat(get("/oauth2/jwks").statusCode()).isEqualTo(200);
    }

    @Test
    void alsoPublishesTheAuthorizationServerMetadataDocument() throws Exception {
        // RFC 8414, which is what a plain OAuth2 client reads. OIDC discovery above is a
        // different document at a different path, and an agent may look for either.
        HttpResponse<String> response = get("/.well-known/oauth-authorization-server");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("issuer").asText())
                .isEqualTo("http://127.0.0.1:9443");
    }
}
