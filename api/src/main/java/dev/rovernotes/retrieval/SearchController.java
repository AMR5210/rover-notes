package dev.rovernotes.retrieval;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.rovernotes.CurrentOwner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only search endpoint.
 *
 * <p>Returns ranked chunks rather than whole documents, and includes the span offsets
 * so a caller can highlight or cite the exact passage. This is also the shape the MCP
 * {@code search} tool returns in Week 6 — compact snippets with stable IDs, because
 * agent context is limited.
 */
@RestController
@RequestMapping("/api/search")
class SearchController {

    private final RetrievalService retrieval;
    private final CurrentOwner owner;

    SearchController(RetrievalService retrieval, CurrentOwner owner) {
        this.retrieval = retrieval;
        this.owner = owner;
    }

    /**
     * @param mode   overrides the configured retrieval mode for one query
     * @param rerank overrides whether the cross-encoder runs
     * @param route  overrides whether the query router picks the channel
     *
     * <p>All three exist so the eval harness can score one stage at a time; left unset,
     * each serves the configured default, which is what production traffic gets.
     *
     * <p>Naming a {@code mode} suppresses the router, since an explicit channel is an
     * instruction rather than a preference. The response reports the mode actually used,
     * so a routed query is visible as such.
     */
    @GetMapping
    SearchResponse search(@RequestParam String q,
                          @RequestParam(defaultValue = "10") int limit,
                          @RequestParam(required = false) String mode,
                          @RequestParam(required = false) Boolean rerank,
                          @RequestParam(required = false) Boolean route) {
        boolean routing = route != null ? route : retrieval.routeByDefault();
        RetrievalMode requested = mode != null && !mode.isBlank() ? parseMode(mode) : null;
        boolean reranking = rerank != null ? rerank : retrieval.rerankByDefault();

        RetrievalService.Result result =
                retrieval.routedSearch(owner.id(), q, limit, requested, reranking, routing);

        return new SearchResponse(q, result.mode(), reranking, result.hits().size(),
                result.hits().stream().map(Hit::from).toList());
    }

    // Parsed here rather than by the framework's enum converter, which matches constant
    // names exactly and would reject the lowercase spelling a caller naturally types.
    private RetrievalMode parseMode(String requested) {
        if (requested == null || requested.isBlank()) {
            return retrieval.defaultMode();
        }
        try {
            return RetrievalMode.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown retrieval mode '%s'; expected one of %s"
                            .formatted(requested, Arrays.toString(RetrievalMode.values())));
        }
    }

    record Hit(
            UUID chunkId,
            UUID documentId,
            String title,
            String snippet,
            int charStart,
            int charEnd,
            double score
    ) {
        static Hit from(RetrievalService.RetrievedChunk c) {
            return new Hit(c.chunkId(), c.documentId(), c.title(), c.content(),
                    c.charStart(), c.charEnd(), c.score());
        }
    }

    record SearchResponse(String query, RetrievalMode mode, boolean reranked, int count,
                          List<Hit> results) {}
}
