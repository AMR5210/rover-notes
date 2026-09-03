package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/**
 * What {@code /api/ask} answers when the model provider refuses, over HTTP.
 *
 * <p>{@link ModelRefusalTest} pins the classification; this pins that it reaches the
 * caller. The two are separate because the failure being fixed lived between them: the
 * status was decided correctly nowhere, and the exception travelled from Spring AI to the
 * dispatcher untouched — {@code AnthropicChatModel} does not wrap it — so every refusal
 * arrived as 500 with its reason only in the log.
 *
 * <p>Over a real port rather than through MockMvc, because the streaming half of this
 * cannot be checked any other way: the response is committed before the model is called,
 * and what the client receives after that point is a matter of what is written to an open
 * connection rather than what a handler returned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AskRefusalTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @MockitoBean
    AnswerService answers;

    @MockitoBean
    AgentAnswerService agent;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();

    /** Verbatim from a live 400, request id req_011CePbjaNJwP9HEdWP8mZ9n. */
    private static final String NO_CREDIT = """
            {"type":"error","error":{"type":"invalid_request_error",\
            "message":"Your credit balance is too low to access the Anthropic API. \
            Please go to Plans & Billing to upgrade or purchase credits."}}""";

    private static JsonValue body(String json) {
        try {
            return JsonValue.fromJsonNode(JSON.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static AnthropicServiceException noCredit() {
        return BadRequestException.builder().headers(Headers.builder().build())
                .body(body(NO_CREDIT)).build();
    }

    private HttpResponse<String> post(String path, String accept) {
        try {
            return http.send(HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + path))
                            .header("content-type", "application/json")
                            .header("accept", accept)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"question\":\"what does the corpus say\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("request failed", cause);
        }
    }

    @Test
    void anExhaustedBalanceAnswers503RatherThan500() {
        when(answers.answer(any(UUID.class), anyString())).thenThrow(noCredit());

        HttpResponse<String> response = post("/api/ask", "application/json");

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void theBodyNamesTheReasonRatherThanLeavingItInTheLog() {
        // The generation eval reported `Server error '500'` for a whole run, and finding
        // out why meant reading the API's log. What it needed was one line of the body.
        when(answers.answer(any(UUID.class), anyString())).thenThrow(noCredit());

        HttpResponse<String> response = post("/api/ask", "application/json");

        assertThat(response.body())
                .contains("credit")
                .contains("Your credit balance is too low");
    }

    @Test
    void aRateLimitAnswers429WithTheWaitInTheHeader() {
        // Retry-After is what a generic HTTP client honours, and a client that has to read
        // a JSON body to learn it will not.
        when(answers.answer(any(UUID.class), anyString())).thenThrow(
                RateLimitException.builder()
                        .headers(Headers.builder().put("retry-after", "30").build())
                        .body(body("""
                                {"type":"error","error":{"type":"rate_limit_error",\
                                "message":"rate limited"}}""")).build());

        HttpResponse<String> response = post("/api/ask", "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("retry-after")).contains("30");
    }

    @Test
    void aRequestThisServiceBuiltWronglyKeepsIts500() {
        // Answering 503 to everything would file this project's own defects under the
        // provider's problems, where nobody is looking for them.
        when(answers.answer(any(UUID.class), anyString())).thenThrow(
                BadRequestException.builder().headers(Headers.builder().build())
                        .body(body("""
                                {"type":"error","error":{"type":"invalid_request_error",\
                                "message":"messages: Field required"}}""")).build());

        HttpResponse<String> response = post("/api/ask", "application/json");

        assertThat(response.statusCode()).isEqualTo(500);
    }

    @Test
    void theStreamSaysWhyItStoppedRatherThanJustStopping() {
        // The response is committed with 200 and the citations before the first token is
        // asked for, so a refusal after that cannot change the status. Without an event
        // carrying it, the stream simply ends — which is exactly what success looks like
        // from the other side, and the reader is left with a blank answer and no reason.
        when(answers.stream(any(UUID.class), anyString())).thenReturn(
                new AnswerService.Stream(List.of(), Flux.error(noCredit())));

        HttpResponse<String> response = post("/api/ask/stream", "text/event-stream");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("event:error")
                .contains("Your credit balance is too low");
    }

    @Test
    void aStreamThatFailsStillCloses() {
        // A client that closes on `done` would otherwise wait on a connection nothing is
        // going to write to again.
        when(answers.stream(any(UUID.class), anyString())).thenReturn(
                new AnswerService.Stream(List.of(), Flux.error(noCredit())));

        assertThat(post("/api/ask/stream", "text/event-stream").body())
                .contains("event:done");
    }
}
