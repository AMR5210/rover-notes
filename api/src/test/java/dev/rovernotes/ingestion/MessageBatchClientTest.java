package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Message Batches API, read and written off the wire.
 *
 * <p>Against a loopback server rather than a mocked client. Spring AI has no batch
 * binding — the resolved jar contains no batch classes — so this is hand-written HTTP,
 * and every part of it that can be wrong is a field name or a header that a mock would
 * agree with whatever it said.
 *
 * <p>The bodies below are literal JSON taken from the API documentation rather than
 * serialised from the records under test. Round-tripping through the same annotations
 * being checked would agree with itself however they were written.
 */
class MessageBatchClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private MessageBatchClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final List<String> versions = new CopyOnWriteArrayList<>();
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages/batches", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            versions.add(String.valueOf(
                    exchange.getRequestHeaders().getFirst("anthropic-version")));
            requests.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));

            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new MessageBatchClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "claude-haiku-4-5", 200, Duration.ofSeconds(10));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static final String IN_PROGRESS = """
            {
              "id": "msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d",
              "type": "message_batch",
              "processing_status": "in_progress",
              "request_counts": {"processing": 2, "succeeded": 0, "errored": 0,
                                 "canceled": 0, "expired": 0},
              "results_url": null
            }
            """;

    private static final String ENDED = """
            {
              "id": "msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d",
              "type": "message_batch",
              "processing_status": "ended",
              "request_counts": {"processing": 0, "succeeded": 2, "errored": 1,
                                 "canceled": 0, "expired": 0},
              "results_url": "https://api.anthropic.com/v1/messages/batches/msgbatch_01/results"
            }
            """;

    @Test
    void aBatchIsSubmittedInTheShapeTheApiDocuments() throws Exception {
        responseBody = IN_PROGRESS;

        client.submit(List.of(
                new MessageBatchClient.Request("chunk-one", "Describe this chunk."),
                new MessageBatchClient.Request("chunk-two", "Describe this one.")));

        JsonNode body = JSON.readTree(requests.getFirst());
        assertThat(body.path("requests")).hasSize(2);

        JsonNode first = body.path("requests").get(0);
        assertThat(first.path("custom_id").asText()).isEqualTo("chunk-one");
        assertThat(first.path("params").path("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(first.path("params").path("max_tokens").asInt()).isEqualTo(200);
        assertThat(first.path("params").path("messages").get(0).path("role").asText())
                .isEqualTo("user");
        assertThat(first.path("params").path("messages").get(0).path("content").asText())
                .isEqualTo("Describe this chunk.");
    }

    @Test
    void theRequestCarriesTheHeadersTheApiRequires() {
        // Without anthropic-version the API refuses the request, and it is easy to omit
        // because nothing in the body hints at it.
        responseBody = IN_PROGRESS;

        client.submit(List.of(new MessageBatchClient.Request("chunk-one", "Describe.")));

        assertThat(apiKeys).containsExactly("test-key");
        assertThat(versions).containsExactly("2023-06-01");
    }

    @Test
    void theProcessingStatusIsReadFromItsSnakeCaseName() {
        // A component named processingStatus deserialises to null against this API, and a
        // poller reading null would never see the batch end — it would wait forever on a
        // batch that finished.
        responseBody = IN_PROGRESS;

        var batch = client.submit(List.of(new MessageBatchClient.Request("a", "b")));

        assertThat(batch.id()).isEqualTo("msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d");
        assertThat(batch.processingStatus()).isEqualTo("in_progress");
        assertThat(batch.ended()).isFalse();
    }

    @Test
    void anEndedBatchReportsItsCountsAndResultsUrl() {
        responseBody = ENDED;

        var batch = client.status("msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d");

        assertThat(batch.ended()).isTrue();
        assertThat(batch.requestCounts().succeeded()).isEqualTo(2);
        assertThat(batch.requestCounts().errored()).isEqualTo(1);
        assertThat(batch.resultsUrl()).contains("/results");
    }

    @Test
    void pollingAsksForTheBatchById() {
        responseBody = ENDED;

        client.status("msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d");

        assertThat(paths).containsExactly(
                "/v1/messages/batches/msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d");
    }

    @Test
    void resultsAreFetchedFromTheBatchsOwnPath() {
        responseBody = "";

        client.results("msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d");

        assertThat(paths).containsExactly(
                "/v1/messages/batches/msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d/results");
    }

    @Test
    void anEmptyBatchIsRefusedBeforeItIsSent() {
        assertThatThrownBy(() -> client.submit(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void aBatchLargerThanTheApiAcceptsIsRefusedWithTheReason() {
        // 100,000 requests or 256 MB, whichever comes first. Refused here rather than by
        // the API, so the caller is told to split rather than shown a 400.
        List<MessageBatchClient.Request> tooMany =
                java.util.stream.IntStream.rangeClosed(0, MessageBatchClient.MAX_REQUESTS_PER_BATCH)
                        .mapToObj(i -> new MessageBatchClient.Request("id-" + i, "x"))
                        .toList();

        assertThatThrownBy(() -> client.submit(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
        assertThat(requests).isEmpty();
    }

    @Test
    void aChunkIdIsAValidCustomId() {
        // custom_id must match ^[a-zA-Z0-9_-]{1,64}$. A UUID does, with its hyphens and
        // no braces — which is what makes a chunk id usable as the key directly.
        assertThat(UUID.randomUUID().toString()).matches("^[a-zA-Z0-9_-]{1,64}$");
    }
}
