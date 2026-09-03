package dev.rovernotes.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.rovernotes.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The Anthropic Message Batches API, over raw HTTP.
 *
 * <p>Spring AI is what every other model call in this project goes through, and it has no
 * binding for batches — checked against the resolved {@code spring-ai-anthropic} jar, which
 * contains no batch classes. So this is the one place that speaks to the API directly.
 *
 * <p>Batches exist for work that is large and not urgent, which is exactly the shape of
 * annotating a corpus at import: thousands of independent calls, nobody waiting, and
 * half the price. The trade is latency measured in minutes to hours rather than seconds,
 * which is why nothing on the read path may use this.
 *
 * <h2>What the API guarantees, and what it does not</h2>
 *
 * <ul>
 *   <li>A batch holds at most 100,000 requests or 256 MB, whichever comes first.</li>
 *   <li>Processing ends within 24 hours; most finish sooner. A batch that has not ended
 *       by then expires, and its finished results are still retrievable.</li>
 *   <li>Results are retained for 29 days after creation.</li>
 *   <li><strong>Results come back in any order.</strong> They are keyed by
 *       {@code custom_id}, never by position — the documentation says so explicitly, and
 *       a caller that zips them against its input list will mislabel every row the moment
 *       the order differs.</li>
 * </ul>
 *
 * <p>{@code custom_id} must match {@code ^[a-zA-Z0-9_-]{1,64}$}, which a UUID does with
 * its hyphens and no braces.
 */
@Component
@ConditionalOnProperty(name = "rover.ingestion.bulk-annotation.enabled", havingValue = "true")
public class MessageBatchClient {

    /** What the API accepts in one batch, so a caller can split before being refused. */
    public static final int MAX_REQUESTS_PER_BATCH = 100_000;

    private final RestClient client;
    private final String model;
    private final int maxTokens;

    MessageBatchClient(@Value("${rover.ingestion.bulk-annotation.base-url:https://api.anthropic.com}")
                       String baseUrl,
                       @Value("${spring.ai.anthropic.api-key:}") String apiKey,
                       @Value("${rover.ingestion.bulk-annotation.model}") String model,
                       @Value("${rover.ingestion.bulk-annotation.max-tokens:200}") int maxTokens,
                       @Value("${rover.ingestion.bulk-annotation.timeout:60s}") Duration timeout) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClients.http11(timeout))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /**
     * Submits a batch and returns it as the API first reports it.
     *
     * <p>The returned batch is almost always {@code in_progress}; the caller polls.
     */
    public Batch submit(List<Request> requests) {
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one request");
        }
        if (requests.size() > MAX_REQUESTS_PER_BATCH) {
            throw new IllegalArgumentException(
                    "a batch holds at most " + MAX_REQUESTS_PER_BATCH + " requests, was "
                            + requests.size() + "; split it before submitting");
        }

        Batch batch = client.post()
                .uri("/v1/messages/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requests", requests.stream().map(this::wire).toList()))
                .retrieve()
                .body(Batch.class);

        if (batch == null) {
            throw new IllegalStateException("the batches API returned an empty body");
        }
        return batch;
    }

    /** The batch as it stands now. {@code processing_status} is what a poller reads. */
    public Batch status(String batchId) {
        Batch batch = client.get()
                .uri("/v1/messages/batches/{id}", batchId)
                .retrieve()
                .body(Batch.class);

        if (batch == null) {
            throw new IllegalStateException("the batches API returned an empty body");
        }
        return batch;
    }

    /**
     * The finished results, one per line of JSONL.
     *
     * <p>Returned whole rather than streamed. A batch here is one corpus import and its
     * results are a few hundred short sentences; the memory that costs is far less than
     * the complexity of a streaming parser, and the size bound is the caller's own batch.
     */
    public String results(String batchId) {
        String body = client.get()
                .uri("/v1/messages/batches/{id}/results", batchId)
                .retrieve()
                .body(String.class);
        return body == null ? "" : body;
    }

    /** One request in a batch: what to ask, and the key its answer comes back under. */
    public record Request(String customId, String prompt) {}

    private Map<String, Object> wire(Request request) {
        return Map.of(
                "custom_id", request.customId(),
                "params", Map.of(
                        "model", model,
                        "max_tokens", maxTokens,
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", request.prompt()))));
    }

    /**
     * A batch as the API reports it.
     *
     * <p>Field names are given explicitly for the same reason the parsing client's are:
     * the API serialises snake_case and nothing here configures a naming strategy, so a
     * component named {@code processingStatus} would deserialise to null and a poller
     * reading it would never see the batch end.
     */
    public record Batch(
            @JsonProperty("id") String id,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("request_counts") Counts requestCounts,
            @JsonProperty("results_url") String resultsUrl) {

        /** True once every request has reached a terminal state. */
        public boolean ended() {
            return "ended".equals(processingStatus);
        }
    }

    /** How the batch's requests are distributed across their outcomes. */
    public record Counts(
            @JsonProperty("processing") int processing,
            @JsonProperty("succeeded") int succeeded,
            @JsonProperty("errored") int errored,
            @JsonProperty("canceled") int canceled,
            @JsonProperty("expired") int expired) {}
}
