package dev.rovernotes.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the reranker client against a real HTTP server on a loopback port.
 *
 * <p>Stubbing at the HTTP boundary rather than mocking the client keeps the JSON contract
 * under test — the request body TEI receives, and the response shape it returns. Those
 * are the parts that break on a model-server upgrade, and a mock would agree with
 * whatever the code happens to send.
 */
class RerankClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(int status, String body, Duration delay) {
        server.createContext("/rerank", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
    }

    private RerankClient client(Duration timeout) {
        return new RerankClient(baseUrl, timeout, 32, "cross-encoder", baseUrl, timeout);
    }

    private RerankClient client(Duration timeout, int batchSize) {
        return new RerankClient(baseUrl, timeout, batchSize, "cross-encoder", baseUrl, timeout);
    }

    /** Points the late-interaction client at the same stub, and selects that strategy. */
    private RerankClient lateClient(Duration timeout) {
        return new RerankClient(baseUrl, timeout, 32, "late-interaction", baseUrl, timeout);
    }

    private static RetrievalService.RetrievedChunk chunk(String content, double score) {
        return new RetrievalService.RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(), "doc", content, 0, content.length(), score);
    }

    private static final List<RetrievalService.RetrievedChunk> FUSED = List.of(
            chunk("first by fusion", 0.9),
            chunk("second by fusion", 0.5),
            chunk("third by fusion", 0.1));

    @Test
    void reordersCandidatesByCrossEncoderScore() {
        // Returned deliberately out of order: the client must sort, not trust the order.
        respond(200, """
                [{"index": 2, "score": 9.5}, {"index": 0, "score": 1.0}, {"index": 1, "score": 4.2}]
                """, Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofSeconds(5))
                .rerank("query", FUSED, 3);

        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("third by fusion", "second by fusion", "first by fusion");
    }

    @Test
    void carriesTheCrossEncoderScoreOntoTheResult() {
        respond(200, """
                [{"index": 0, "score": 7.25}, {"index": 1, "score": 2.0}, {"index": 2, "score": 1.0}]
                """, Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofSeconds(5))
                .rerank("query", FUSED, 1);

        assertThat(result).singleElement()
                .extracting(RetrievalService.RetrievedChunk::score)
                .isEqualTo(7.25);
    }

    @Test
    void keepsOnlyTheRequestedNumberOfResults() {
        respond(200, """
                [{"index": 0, "score": 3.0}, {"index": 1, "score": 2.0}, {"index": 2, "score": 1.0}]
                """, Duration.ZERO);

        assertThat(client(Duration.ofSeconds(5)).rerank("query", FUSED, 2)).hasSize(2);
    }

    @Test
    void sendsTheRequestShapeTeiExpects() {
        respond(200, "[{\"index\": 0, \"score\": 1.0}]", Duration.ZERO);

        client(Duration.ofSeconds(5)).rerank("how are lists combined", FUSED, 3);

        assertThat(lastRequestBody.get())
                .contains("\"query\":\"how are lists combined\"")
                .contains("\"first by fusion\"")
                .contains("\"third by fusion\"")
                // Chunks can exceed the model's 512-token window; scoring a long chunk on
                // its first 512 tokens beats having the request rejected.
                .contains("\"truncate\":true");
    }

    @Test
    void fallsBackToTheFusedRankingWhenTheServerErrors() {
        respond(500, "{\"error\":\"model not loaded\"}", Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofSeconds(5))
                .rerank("query", FUSED, 3);

        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("first by fusion", "second by fusion", "third by fusion");
    }

    @Test
    void fallsBackToTheFusedRankingWhenTheServerIsTooSlow() {
        respond(200, "[{\"index\": 2, \"score\": 9.9}]", Duration.ofMillis(400));

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofMillis(100))
                .rerank("query", FUSED, 3);

        // Search stays available and loses only the precision this stage would have added.
        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("first by fusion", "second by fusion", "third by fusion");
    }

    @Test
    void fallsBackWhenNothingIsListening() {
        server.stop(0);

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofMillis(200))
                .rerank("query", FUSED, 2);

        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("first by fusion", "second by fusion");
    }

    @Test
    void splitsCandidatesIntoBatchesTheServerWillAccept() {
        // TEI's max_client_batch_size defaults to 32, below the 40 candidates this stage
        // scores. An over-sized request is a 422, which falls back to the fused ranking —
        // so without batching the reranker degrades silently instead of failing.
        java.util.List<Integer> batchSizes = new java.util.ArrayList<>();
        server.createContext("/rerank", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // Count the candidates actually submitted rather than inferring from
            // punctuation: the request also carries the query and the truncate flag.
            int count = body.split("candidate ", -1).length - 1;
            batchSizes.add(count);
            StringBuilder payload = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                payload.append(i > 0 ? "," : "")
                       .append("{\"index\":").append(i).append(",\"score\":").append(count - i).append("}");
            }
            payload.append("]");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        List<RetrievalService.RetrievedChunk> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(chunk("candidate " + i, 1.0 / (i + 1)));
        }

        List<RetrievalService.RetrievedChunk> result =
                client(Duration.ofSeconds(5), 32).rerank("query", many, 10);

        assertThat(batchSizes).containsExactly(32, 8);
        assertThat(result).hasSize(10);
    }

    @Test
    void doesNotCallTheModelForASingleCandidate() {
        respond(200, "[{\"index\": 0, \"score\": 9.9}]", Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result = client(Duration.ofSeconds(5))
                .rerank("query", FUSED.subList(0, 1), 10);

        assertThat(result).hasSize(1);
        assertThat(lastRequestBody.get()).isNull();
    }

    // ------------------------------------------------- reranking by late interaction

    @Test
    void theLateInteractionStrategyReadsTheWrappedResponseShape() {
        // The Python service answers {"results": [...], "strategy": ...} where TEI answers
        // a bare array. Reading one shape as the other is the failure this pins: Jackson
        // would produce an empty list rather than an error, and the fused ranking would
        // be served while the reranker looked healthy.
        respond(200, """
                {"results": [{"index": 2, "score": 9.5}, {"index": 0, "score": 1.0}],
                 "strategy": "late-interaction"}
                """, Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result =
                lateClient(Duration.ofSeconds(5)).rerank("q", FUSED, 3);

        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("third by fusion", "first by fusion");
        assertThat(result.getFirst().score()).isEqualTo(9.5);
    }

    @Test
    void theLateInteractionStrategyAsksForItByName() {
        respond(200, """
                {"results": [{"index": 0, "score": 1.0}], "strategy": "late-interaction"}
                """, Duration.ZERO);

        lateClient(Duration.ofSeconds(5)).rerank("what is PARROTVALVE?", FUSED, 3);

        // `documents` rather than `texts`, and the strategy named: the service defaults to
        // the cross-encoder, so omitting it would silently measure the wrong reranker.
        assertThat(lastRequestBody.get()).contains("\"documents\"");
        assertThat(lastRequestBody.get()).contains("\"strategy\":\"late-interaction\"");
        assertThat(lastRequestBody.get()).contains("\"top_k\"");
    }

    @Test
    void anUninstalledLateInteractionExtraCostsPrecisionRatherThanTheSearch() {
        // 501 is what the service answers where it was installed without the extra. The
        // ranking is already usable, so the search should still return one.
        respond(501, "{\"detail\":\"installed without the late-interaction extra\"}",
                Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result =
                lateClient(Duration.ofSeconds(5)).rerank("q", FUSED, 3);

        assertThat(result).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("first by fusion", "second by fusion", "third by fusion");
    }

    @Test
    void aSlowModelLoadCostsPrecisionRatherThanTheSearch() {
        // The first call after a restart loads the model. Beyond the budget it degrades,
        // like the cross-encoder does, rather than holding the request open.
        respond(200, """
                {"results": [{"index": 0, "score": 1.0}], "strategy": "late-interaction"}
                """, Duration.ofMillis(400));

        List<RetrievalService.RetrievedChunk> result =
                lateClient(Duration.ofMillis(100)).rerank("q", FUSED, 3);

        assertThat(result).hasSize(3);
        assertThat(result.getFirst().content()).isEqualTo("first by fusion");
    }

    @Test
    void theCrossEncoderRemainsTheDefaultStrategy() {
        // Every measurement in `docs/RESULTS.md` was taken against the cross-encoder. A
        // default that moved silently would make them describe a system that no longer
        // exists.
        respond(200, """
                [{"index": 0, "score": 3.0}, {"index": 1, "score": 2.0}, {"index": 2, "score": 1.0}]
                """, Duration.ZERO);

        List<RetrievalService.RetrievedChunk> result =
                client(Duration.ofSeconds(5)).rerank("q", FUSED, 3);

        assertThat(lastRequestBody.get()).contains("\"texts\"");
        assertThat(result).hasSize(3);
    }
}
