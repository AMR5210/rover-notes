package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Which path {@code /api/ask} takes, decided over HTTP.
 *
 * <p>This is the link the generation eval rests on and the one nothing covered. The
 * harness compares the loop against the single pass by sending the same questions twice,
 * once with {@code ?agent=true}, and every other test of the loop calls
 * {@link AgentAnswerService} directly. If the parameter did not reach the branch — unbound,
 * misread, or overridden by the disabled default — both halves of that comparison would run
 * the single pass, the run would cost a full budget of model calls, and the result would be
 * a difference of zero that meant nothing about the loop.
 *
 * <p>Both services are mocked, because what is under test is the routing rather than either
 * answer. That also keeps this off the API: a test that spent credit to check which branch
 * was taken would be its own kind of mistake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AskRoutingTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @MockitoBean
    AgentAnswerService agent;

    @MockitoBean
    AnswerService answers;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final AnswerService.Answer ANSWER =
            new AnswerService.Answer("an answer", List.of());

    private HttpResponse<String> ask(String query) {
        when(agent.answer(any(UUID.class), anyString())).thenReturn(ANSWER);
        when(answers.answer(any(UUID.class), anyString())).thenReturn(ANSWER);
        try {
            return http.send(HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/ask" + query))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"question\":\"what does the corpus say\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException cause) {
            throw new IllegalStateException("request failed", cause);
        }
    }

    @Test
    void agentTrueReachesTheLoopRatherThanTheSinglePass() {
        assertThat(ask("?agent=true").statusCode()).isEqualTo(200);

        verify(agent).answer(any(UUID.class), anyString());
        verify(answers, never()).answer(any(UUID.class), anyString());
    }

    @Test
    void theDefaultIsStillTheSinglePass() {
        // rover.agent.enabled is false, so a question with no parameter must not reach the
        // loop. The eval sends the same questions both ways in one run; if the default
        // changed underneath it, the two halves would be the same measurement.
        assertThat(ask("").statusCode()).isEqualTo(200);

        verify(answers).answer(any(UUID.class), anyString());
        verify(agent, never()).answer(any(UUID.class), anyString());
    }

    @Test
    void agentFalseIsHonouredRatherThanTreatedAsAbsent() {
        assertThat(ask("?agent=false").statusCode()).isEqualTo(200);

        verify(answers).answer(any(UUID.class), anyString());
        verify(agent, never()).answer(any(UUID.class), anyString());
    }
}
