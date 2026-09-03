package dev.rovernotes.retrieval;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.client.RestClientException;

/**
 * Client for the cross-encoder reranker, served by Text Embeddings Inference.
 *
 * <p>Where the retrieval channels encode query and chunk separately and compare the
 * results, a cross-encoder reads the pair together and scores their relevance directly.
 * That is markedly more accurate and far too slow to run over a whole corpus, which is
 * why it only ever sees the fused candidates. See docs/ARCHITECTURE.md.
 *
 * <p>Request and response shapes are taken from the TEI OpenAPI specification served at
 * {@code /api-doc/openapi.json}: POST {@code /rerank} accepts
 * {@code {"query": ..., "texts": [...]}} and returns {@code [{"index", "score"}]}, where
 * {@code index} refers back into the submitted {@code texts} array.
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** The value of {@code rover.ml.rerank-strategy} that selects the ColBERT reranker. */
    private static final String LATE_INTERACTION = "late-interaction";

    private final RestClient client;
    private final RestClient lateInteraction;
    private final int batchSize;
    private final String strategy;

    RerankClient(@Value("${rover.ml.reranker-url}") String baseUrl,
                 @Value("${rover.ml.reranker-timeout}") Duration timeout,
                 @Value("${rover.ml.reranker-batch-size}") int batchSize,
                 @Value("${rover.ml.rerank-strategy:cross-encoder}") String strategy,
                 @Value("${rover.ml.late-interaction-url:http://localhost:8000}")
                 String lateInteractionUrl,
                 @Value("${rover.ml.late-interaction-timeout:30s}") Duration lateTimeout) {
        // A reranker that hangs must not hang the request. The cross-encoder is a
        // precision refinement over an already-usable ranking, so a timeout should cost
        // result quality rather than availability — see rerank() for the fallback.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.batchSize = batchSize;
        this.strategy = strategy;

        // A separate client because it is a separate service on a separate budget. The
        // first call after a restart loads a model rather than scoring anything, so the
        // cross-encoder's five seconds would time it out and serve the fused ranking to
        // whoever searched first after a deploy.
        SimpleClientHttpRequestFactory lateFactory = new SimpleClientHttpRequestFactory();
        lateFactory.setConnectTimeout(lateTimeout);
        lateFactory.setReadTimeout(lateTimeout);
        this.lateInteraction = RestClient.builder()
                .baseUrl(lateInteractionUrl)
                .requestFactory(lateFactory)
                .build();
    }

    /**
     * Reorders {@code candidates} by cross-encoder relevance to {@code query}.
     *
     * <p>Returns the fused order unchanged if the reranker is unreachable.
     * Degrading to the input ranking keeps search answering during a model-server
     * restart, at the cost of the precision this stage adds; the fallback is logged so
     * the loss shows up in operations rather than silently in the metrics.
     *
     * @param topN how many of the reranked results to keep
     */
    public List<RetrievalService.RetrievedChunk> rerank(
            String query, List<RetrievalService.RetrievedChunk> candidates, int topN) {

        // Whether to rerank at all is the caller's decision; this client only ever
        // answers how. A single candidate has no ordering to improve.
        if (candidates.size() <= 1) {
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        if (LATE_INTERACTION.equals(strategy)) {
            return rerankByLateInteraction(query, candidates, topN);
        }

        // TEI rejects a request carrying more texts than its max_client_batch_size, which
        // defaults to 32 — below the 40 candidates this stage is configured to score. The
        // rejection is a 422, which the catch below turns into the fused ranking, so an
        // over-sized request degrades silently rather than failing loudly. A cross-encoder
        // scores each pair independently, so splitting the batch changes nothing about the
        // scores and removes the dependence on a server-side setting.
        List<Scored> scored = new java.util.ArrayList<>(candidates.size());
        for (int start = 0; start < candidates.size(); start += batchSize) {
            int end = Math.min(start + batchSize, candidates.size());
            List<RetrievalService.RetrievedChunk> batch = candidates.subList(start, end);
            List<String> texts = batch.stream()
                    .map(RetrievalService.RetrievedChunk::content)
                    .toList();

            Rank[] ranks;
            try {
                ranks = client.post()
                        .uri("/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        // truncate: chunks can exceed the model's 512-token window, and
                        // scoring a long chunk on its first 512 tokens beats rejecting it.
                        .body(new RerankRequest(query, texts, true))
                        .retrieve()
                        .body(Rank[].class);
            } catch (RestClientException e) {
                log.warn("Reranker unavailable, serving the fused ranking: {}", e.getMessage());
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }
            if (ranks == null) {
                log.warn("Reranker returned no body, serving the fused ranking");
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }
            for (Rank rank : ranks) {
                scored.add(new Scored(batch.get(rank.index()), rank.score()));
            }
        }

        return scored.stream()
                .sorted(java.util.Comparator.comparingDouble(Scored::score).reversed())
                .limit(topN)
                .map(s -> s.chunk().withScore(s.score()))
                .toList();
    }

    /**
     * Reorders by MaxSim against a ColBERT model, served by the Python service.
     *
     * <p>Not batched. The batching above exists because TEI refuses a request carrying
     * more texts than its client batch size; this scores in the service's own process,
     * where the whole candidate list is one call and one model load.
     *
     * <p>Falls back to the fused ranking on the same terms as the cross-encoder, and for
     * one more reason: the strategy is answered 501 where the service was installed
     * without the extra that provides it, which should cost precision rather than the
     * search.
     */
    private List<RetrievalService.RetrievedChunk> rerankByLateInteraction(
            String query, List<RetrievalService.RetrievedChunk> candidates, int topN) {

        List<String> texts = candidates.stream()
                .map(RetrievalService.RetrievedChunk::content)
                .toList();

        LateResponse response;
        try {
            response = lateInteraction.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LateRequest(query, texts, topN, LATE_INTERACTION))
                    .retrieve()
                    .body(LateResponse.class);
        } catch (RestClientException e) {
            log.warn("Late-interaction reranker unavailable, serving the fused ranking: {}",
                    e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        if (response == null || response.results() == null) {
            log.warn("Late-interaction reranker returned no body, serving the fused ranking");
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        return response.results().stream()
                .limit(topN)
                .map(rank -> candidates.get(rank.index()).withScore(rank.score()))
                .toList();
    }

    private record RerankRequest(String query, List<String> texts, boolean truncate) {}

    /**
     * The Python service's contract, which is not TEI's: the field is {@code documents}
     * rather than {@code texts}, and the ranks arrive wrapped rather than bare.
     */
    private record LateRequest(String query, List<String> documents,
                               @JsonProperty("top_k") int topK, String strategy) {}

    private record LateResponse(List<Rank> results, String strategy) {}

    /** A candidate paired with its cross-encoder score, before the batches are merged. */
    private record Scored(RetrievalService.RetrievedChunk chunk, double score) {}

    /** One scored candidate; {@code index} points back into the submitted texts. */
    private record Rank(int index, double score) {}
}
