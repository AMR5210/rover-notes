package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The two pages a person meets before they have a token.
 *
 * <p>The grant tests already sign in through this form, so that it works is covered. What
 * is covered here is what those tests cannot see: that the page is this project's rather
 * than the framework's, that a refusal says the same thing whichever half was wrong, and
 * that {@code /logout} still answers at all.
 *
 * <p>That last one is the reason this class exists. Spring registers its generated logout
 * page only while it is also serving the generated sign-in form —
 * {@code DefaultLoginPageConfigurer} adds both inside one branch — so naming a login page
 * silently withdrew it. The interface signs out by navigating to {@code /logout}, nothing
 * else asserts on it, and the failure would have been a dead link found by a person.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class SignInPageTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> get(String path) {
        try {
            return http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("request to " + path + " failed", cause);
        }
    }

    @Test
    void servesThisProjectsSignInPageRatherThanTheGeneratedOne() {
        HttpResponse<String> response = get("/login");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("text/html");
        // The generated form has no way to offer these; they are the reason for replacing
        // it, since the pages that create and recover an account are otherwise reachable
        // only from a link in an email.
        assertThat(response.body())
                .contains("/account/register")
                .contains("/account/reset-request");
        assertThat(response.body())
                .as("the stylesheet Spring serves beside its own pages is not registered here")
                .doesNotContain("default-ui.css");
    }

    @Test
    void carriesTheTokenTheFormPostsBack() {
        // Name before value, which is the order the grant tests read it out of. CSRF is on
        // for this chain, so a form without it is a form that cannot be submitted.
        assertThat(get("/login").body())
                .containsPattern("name=\"_csrf\"[^>]*value=\"[^\"]+\"");
    }

    @Test
    void saysTheSameThingWhicheverHalfOfTheCredentialsWasWrong() {
        String body = get("/login?error").body();

        assertThat(body).contains("not recognised");
        // Naming the address as unknown, or the password as wrong, answers "is this address
        // registered" to anybody who asks — the question every other endpoint here declines.
        assertThat(body.toLowerCase())
                .doesNotContain("no such account")
                .doesNotContain("unknown email")
                .doesNotContain("incorrect password")
                .doesNotContain("wrong password");
    }

    @Test
    void confirmsAFinishedSessionRatherThanLookingLikeAFailure() {
        // Sign-out lands back here with ?logout. Without a message the page reads as though
        // signing in had failed, which is the opposite of what happened.
        assertThat(get("/login?logout").body()).contains("signed out");
    }

    @Test
    void stillAnswersAtLogout() {
        HttpResponse<String> response = get("/logout");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<form method=\"post\" action=\"/logout\"");
    }

    @Test
    void keepsSignOutBehindAPostWithAToken() {
        // A GET that ends a session is one any other site can trigger with an image tag.
        // The cost of getting this wrong is small — a signed-out session — but the shape is
        // the shape of every worse version of the same mistake.
        assertThat(get("/logout").body())
                .containsPattern("name=\"_csrf\"[^>]*value=\"[^\"]+\"");
    }
}
