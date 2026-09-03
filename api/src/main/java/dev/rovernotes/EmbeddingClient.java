package dev.rovernotes;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for Hugging Face Text Embeddings Inference.
 *
 * <p>Calls TEI directly rather than proxying through the Python service. TEI already
 * provides dynamic batching, ONNX execution, and warm model loading over HTTP, so an
 * intermediate hop would add latency and another failure point without adding
 * capability. The Python service keeps document parsing and the eval harness, where it
 * contributes something TEI does not.
 *
 * <p>Request and response shapes are taken from the TEI OpenAPI specification: POST
 * {@code /embed} accepts {@code {"inputs": [...]}} and returns a bare array of float
 * arrays, one per input, in request order.
 *
 * <p>Lives in the root package because both ingestion and retrieval embed text — one
 * for chunks, one for queries — using the same model. Placing it in either module would
 * make the other depend on it for a reason unrelated to that module's purpose.
 */
@Component
public class EmbeddingClient {

    private final RestClient queries;
    private final RestClient batches;
    private final int expectedDimensions;

    /**
     * Two clients, because the two callers want opposite things from a slow server.
     *
     * <p>A query embedding is on the read path, where a caller is waiting and the whole
     * retrieval budget is 150 ms; past a second the useful move is to stop waiting and
     * answer from the lexical channel. A batch embedding is ingestion, where nothing is
     * waiting, a batch of 32 texts legitimately takes far longer than one, and giving up
     * early just means the outbox retries work that would have succeeded.
     *
     * <p>Neither timeout is a latency control. They exist so that an embedding server
     * which stops answering costs a bounded amount rather than holding request threads
     * until the socket does.
     */
    EmbeddingClient(@Value("${rover.ml.embeddings-url}") String baseUrl,
                    @Value("${rover.ml.embedding-dimensions}") int expectedDimensions,
                    @Value("${rover.ml.query-timeout}") Duration queryTimeout,
                    @Value("${rover.ml.ingest-timeout}") Duration ingestTimeout) {
        this.queries = restClient(baseUrl, queryTimeout);
        this.batches = restClient(baseUrl, ingestTimeout);
        this.expectedDimensions = expectedDimensions;
    }

    private static RestClient restClient(String baseUrl, Duration readTimeout) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClients.http11(readTimeout))
                .build();
    }

    /**
     * Embeds a batch of texts.
     *
     * @return one vector per input, in the same order
     * @throws IllegalStateException if the model returns a dimension other than the
     *         configured one. Failing here is deliberate: the alternative surfaces much
     *         later as an opaque insert error against the {@code vector(384)} column.
     */
    public List<float[]> embed(List<String> texts) {
        return embed(texts, batches);
    }

    /**
     * Embeds one text under the read-path timeout.
     *
     * <p>Separate from {@link #embed(List)} so a query is not held by the generous bound
     * that ingestion needs. Callers on the read path are expected to handle failure —
     * {@code RetrievalService} answers from the lexical channel instead.
     */
    public float[] embedOne(String text) {
        return embed(List.of(text), queries).getFirst();
    }

    private List<float[]> embed(List<String> texts, RestClient client) {
        if (texts.isEmpty()) {
            return List.of();
        }

        float[][] response = client.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedRequest(texts))
                .retrieve()
                .body(float[][].class);

        if (response == null || response.length != texts.size()) {
            throw new IllegalStateException(
                    "Embedding service returned %d vectors for %d inputs"
                            .formatted(response == null ? 0 : response.length, texts.size()));
        }
        if (response[0].length != expectedDimensions) {
            throw new IllegalStateException(
                    "Embedding service returned %d dimensions, schema expects %d"
                            .formatted(response[0].length, expectedDimensions));
        }
        return List.of(response);
    }

    private record EmbedRequest(List<String> inputs) {}
}
