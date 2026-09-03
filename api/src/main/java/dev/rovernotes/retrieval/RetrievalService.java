package dev.rovernotes.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pgvector.PGvector;
import dev.rovernotes.EmbeddingClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Retrieval over the indexed corpus.
 *
 * <p>Two channels answer different kinds of question. The dense channel embeds the query
 * with the model used at ingest and compares it with pgvector's cosine distance operator
 * {@code <=>}, served by the HNSW index; it is strong on topical similarity, where the
 * answer shares no vocabulary with the question. The lexical channel scores the query
 * against the generated {@code tsv} column, matching literal tokens. That is what
 * carries known-item lookups for identifiers, error codes, and file paths — exactly
 * where a dense embedding compresses the distinguishing token into general topical
 * meaning. Note that PostgreSQL computes no IDF: {@code ts_rank} and {@code ts_rank_cd}
 * are immutable functions of a tsvector and a tsquery and see no corpus statistics, so
 * a rare token helps by matching selectively rather than by scoring higher.
 *
 * <p>{@link RetrievalMode#HYBRID} merges them with Reciprocal Rank Fusion. RRF combines
 * <em>ranks</em> rather than scores, so an unbounded lexical rank value never has to be
 * normalised against a cosine similarity bounded in [-1, 1]. See docs/ARCHITECTURE.md.
 *
 * <p>Every ordering breaks ties on {@code content_hash}. Fusion produces a great many
 * ties on a small corpus, and tie-breaking on {@code id} let a randomly generated UUID
 * decide the ranking: the eval harness measured 0.9308 and 0.9541 nDCG@10 for identical
 * code over identical documents, differing only in which UUIDs that ingest happened to
 * assign. The hash is derived from the content, so it is stable across re-ingests and
 * the metric measures retrieval rather than luck.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final Counter degraded;

    /**
     * Where a search's time goes, split at the one place it can be split.
     *
     * <p>The read path has a 150 ms p95 budget and misses it under concurrency — 183 to
     * 273 ms at 20 users, measured with k6. What that measurement could not say is which
     * stage the time is in, because nothing timed the stages: the only figure available
     * was the one for the whole request, and a budget that fails without an attribution
     * gives nothing to act on.
     *
     * <p>Two timers rather than three. {@code embed} is the call to the embedding server,
     * and {@code candidates} is the whole candidate fetch — which contains the embedding,
     * because the vector is bound as a parameter of the statement that uses it. The SQL's
     * own share is therefore the difference between them rather than a timer of its own,
     * which is the honest way to report it without moving the call to suit the
     * measurement.
     */
    private final Timer embedTimer;

    private final Timer candidateTimer;
    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final RerankClient reranker;
    private final QueryRouter router;
    private final boolean routeByDefault;
    private final RetrievalMode mode;
    private final boolean rerankByDefault;
    private final int defaultLimit;
    private final int candidateLimit;
    private final int rerankLimit;
    private final int rrfK;
    private final int efSearch;
    private final FusionStrategy fusion;
    private final double convexAlpha;
    private final TransactionTemplate transactions;

    RetrievalService(JdbcClient jdbc,
                     EmbeddingClient embeddings,
                     RerankClient reranker,
                     QueryRouter router,
                     @Value("${rover.retrieval.route-enabled}") boolean routeByDefault,
                     @Value("${rover.retrieval.mode}") RetrievalMode mode,
                     @Value("${rover.retrieval.rerank-enabled}") boolean rerankByDefault,
                     @Value("${rover.retrieval.final-limit}") int defaultLimit,
                     @Value("${rover.retrieval.candidate-limit}") int candidateLimit,
                     @Value("${rover.retrieval.rerank-limit}") int rerankLimit,
                     @Value("${rover.retrieval.rrf-k}") int rrfK,
                     @Value("${rover.retrieval.ef-search}") int efSearch,
                     @Value("${rover.retrieval.fusion}") FusionStrategy fusion,
                     @Value("${rover.retrieval.convex-alpha}") double convexAlpha,
                     PlatformTransactionManager transactionManager,
                     MeterRegistry meters) {
        this.degraded = Counter.builder("rover.retrieval.degraded")
                .description("searches answered from the lexical channel because "
                        + "embedding was unavailable")
                .register(meters);
        // Percentile histograms, because the budget is stated at p95 and an average hides
        // exactly the requests it is about.
        this.embedTimer = Timer.builder("rover.retrieval.embed")
                .description("embedding a query, on the read path")
                .publishPercentileHistogram()
                .register(meters);
        this.candidateTimer = Timer.builder("rover.retrieval.candidates")
                .description("fetching the candidate list, embedding included")
                .publishPercentileHistogram()
                .register(meters);
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.router = router;
        this.routeByDefault = routeByDefault;
        this.mode = mode;
        this.rerankByDefault = rerankByDefault;
        this.defaultLimit = defaultLimit;
        this.candidateLimit = candidateLimit;
        this.rerankLimit = rerankLimit;
        this.rrfK = rrfK;
        this.efSearch = efSearch;
        this.fusion = fusion;
        this.convexAlpha = convexAlpha;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    /** The configured mode, served when a caller does not ask for a specific one. */
    public RetrievalMode defaultMode() {
        return mode;
    }

    /** Whether the router runs when a caller does not say. */
    public boolean routeByDefault() {
        return routeByDefault;
    }

    /**
     * The channel the router picks for a query, before any fallback.
     *
     * <p>An explicit {@code mode} parameter always wins over the router: it is how the
     * eval harness scores one channel at a time, and a stage that overrode it would make
     * those runs unreadable. Routing therefore replaces the configured default only.
     */
    public RetrievalMode modeFor(String query, boolean route) {
        return route ? router.route(query).orElse(mode) : mode;
    }

    /**
     * Whether reranking runs when a caller does not say.
     *
     * <p>This is a default rather than a master switch: a caller passing {@code rerank}
     * explicitly gets what it asked for either way, which is what lets the eval harness
     * score the stage on and off against one running instance.
     */
    public boolean rerankByDefault() {
        return rerankByDefault;
    }

    public List<RetrievedChunk> search(UUID ownerId, String query) {
        return search(ownerId, query, defaultLimit);
    }

    /**
     * @param limit maximum chunks to return; clamped to a sane range so a caller cannot
     *              request the whole corpus
     */
    public List<RetrievedChunk> search(UUID ownerId, String query, int limit) {
        return routedSearch(ownerId, query, limit, null, rerankByDefault, routeByDefault).hits();
    }

    /**
     * Applies the router, then searches, falling back when routing found nothing.
     *
     * <p>A {@code requested} mode suppresses the router entirely: an explicit channel is
     * an instruction, and it is how the eval harness scores one channel at a time.
     *
     * <p>The fallback exists because the lexical channel can return nothing at all.
     * PostgreSQL's parser keeps some compound tokens whole — {@code BAAI/bge-small-en-v1.5}
     * and {@code LISTEN/NOTIFY} are each a single lexeme — so a query for one fragment of
     * them matches no lexeme and the channel is empty. Without the fallback the router
     * turns a document the fused ranking would have found into no result at all; measured
     * on the held-out identifier suite that cost 2 of 80 queries their document entirely,
     * while recall for every other query was unaffected. Re-running the configured mode
     * restores exactly the behaviour routing replaced, and cannot affect a routed query
     * that did return results.
     */
    public Result routedSearch(UUID ownerId, String query, int limit,
                               RetrievalMode requested, boolean rerank, boolean route) {
        if (requested != null) {
            return new Result(requested, search(ownerId, query, limit, requested, rerank));
        }
        if (route) {
            Optional<RetrievalMode> routed = router.route(query);
            if (routed.isPresent()) {
                List<RetrievedChunk> hits = search(ownerId, query, limit, routed.get(), rerank);
                if (!hits.isEmpty()) {
                    return new Result(routed.get(), hits);
                }
            }
        }
        return withoutEmbeddings(ownerId, query, limit, rerank);
    }

    /**
     * Serves the configured mode, falling back to the lexical channel if embedding fails.
     *
     * <p>Both channels that need an embedding are unavailable when the embedding server
     * is; the lexical one is not, and it is not a token gesture. Measured on this corpus
     * it scores 0.7930 nDCG@10 against 0.8254 for fusion, keeping 96% of the quality — a
     * worse answer, and far closer to the real one than the alternative, which is no
     * answer at all. The same
     * trade is already made for reranking: a slow model server should cost result quality
     * rather than availability.
     *
     * <p>The fallback is not silent. {@link Result#mode()} is what the search response
     * reports, so a degraded request says {@code LEXICAL} where it would have said
     * {@code HYBRID}, and {@code rover.retrieval.degraded} counts them for anyone not
     * reading individual responses. Silent quality loss is the failure this project is
     * built to avoid.
     */
    private Result withoutEmbeddings(UUID ownerId, String query, int limit, boolean rerank) {
        if (mode == RetrievalMode.LEXICAL) {
            return new Result(mode, search(ownerId, query, limit, mode, rerank));
        }
        try {
            return new Result(mode, search(ownerId, query, limit, mode, rerank));
        } catch (RestClientException e) {
            degraded.increment();
            log.warn("embedding unavailable, answering from the lexical channel: {}",
                    e.getMessage());
            return new Result(RetrievalMode.LEXICAL,
                    search(ownerId, query, limit, RetrievalMode.LEXICAL, rerank));
        }
    }

    /**
     * Hits and the mode that produced them.
     *
     * <p>The mode is returned rather than recomputed because the fallback makes it
     * depend on what the routed channel found, and asking a second time would mean a
     * second query.
     */
    public record Result(RetrievalMode mode, List<RetrievedChunk> hits) {}

    /**
     * Runs a single query against an explicit configuration, which is what the eval
     * harness uses to attribute a change to the stage that produced it.
     *
     * <p>Deliberately not {@code @Transactional}: with reranking on, this method makes an
     * HTTP call to the model server, and a transaction would hold a pooled connection
     * open across it. Each channel is a single statement, so a read-only transaction adds
     * no consistency the statement does not already have.
     */
    public List<RetrievedChunk> search(UUID ownerId, String query, int limit,
                                       RetrievalMode using, boolean rerank) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int capped = Math.clamp(limit, 1, 100);

        // Reranking can only reorder what it is given, so the cross-encoder gets a deeper
        // slice than the caller asked for. Depth is what lets a chunk the fused ranking
        // put at 30 surface in the top 10.
        int fetch = rerank ? Math.max(capped, rerankLimit) : capped;

        List<RetrievedChunk> candidates = candidates(ownerId, query, fetch, using);

        return rerank ? reranker.rerank(query, candidates, capped) : candidates;
    }

    /**
     * Fetches the candidate list, applying {@code hnsw.ef_search} for the duration.
     *
     * <p>{@code ef_search} bounds the size of the candidate list the HNSW scan keeps, and
     * pgvector documents that it must be at least the requested limit. The server default
     * is 40 while the dense channel asks for {@code candidate-limit} rows, so leaving it
     * unset caps how deep that channel can actually see.
     *
     * <p>It is a session setting, and connections are pooled, so it is applied with
     * {@code SET LOCAL} inside a transaction that ends with the query — a session-level
     * {@code SET} would leak to whatever ran next on the same connection. The transaction
     * wraps only the SQL: reranking makes an HTTP call and must not hold a connection.
     */
    private List<RetrievedChunk> candidates(UUID ownerId, String query, int fetch,
                                            RetrievalMode using) {
        return candidateTimer.record(() -> {
            if (efSearch <= 0) {
                return channel(ownerId, query, fetch, using);
            }
            return transactions.execute(status -> {
                // Not a bind parameter: SET does not accept them. The value is an int from
                // configuration, clamped to the range pgvector accepts.
                jdbc.sql("set local hnsw.ef_search = " + Math.clamp(efSearch, 1, 1000)).update();
                return channel(ownerId, query, fetch, using);
            });
        });
    }

    private List<RetrievedChunk> channel(UUID ownerId, String query, int limit,
                                         RetrievalMode using) {
        return switch (using) {
            case DENSE -> dense(ownerId, query, limit);
            case LEXICAL -> lexical(ownerId, query, limit);
            case HYBRID -> switch (fusion) {
                case RRF -> hybrid(ownerId, query, limit);
                case CONVEX -> convex(ownerId, query, limit);
            };
        };
    }

    private List<RetrievedChunk> dense(UUID ownerId, String query, int limit) {
        return jdbc.sql("""
                select c.id            as chunk_id,
                       c.document_id   as document_id,
                       d.title         as title,
                       c.content       as content,
                       c.char_start    as char_start,
                       c.char_end      as char_end,
                       1 - (c.embedding <=> :query) as score
                  from chunks c
                  join documents d on d.id = c.document_id
                 where c.owner_id = :ownerId
                   and c.embedding is not null
                 order by c.embedding <=> :query, c.content_hash
                 limit :limit
                """)
                .param("ownerId", ownerId)
                .param("query", embed(query))
                .param("limit", limit)
                .query(RetrievalService::toChunk)
                .list();
    }

    /**
     * The query lexemes joined with {@code |}, so a chunk matching any term is a
     * candidate.
     *
     * <p>{@code websearch_to_tsquery} and {@code plainto_tsquery} both join terms with
     * {@code &}, which requires every non-stop word to appear in the same chunk. Measured
     * against the golden set, that returned nothing for 19 of 27 natural-language
     * questions — a question phrased as a sentence rarely has all of its words in the
     * passage that answers it. Disjunction is also what the channel is here to imitate:
     * BM25 scores any document containing any query term and lets IDF sort them out.
     * Precision comes from ranking and from fusion, not from the filter. PostgreSQL has
     * no IDF of its own, so the ranking half of that bargain is weaker here than BM25's
     * — see the deferred options in docs/ARCHITECTURE.md.
     *
     * <p>{@code to_tsvector} normalises and drops stop words without ever raising on
     * arbitrary input, so bare punctuation or a stop-word-only query yields no lexemes.
     * {@code nullif} turns that into a null tsquery, and {@code tsv @@ null} matches
     * nothing — the intended answer, and quieter than casting an empty string.
     *
     * <p>The lexemes are parsed under the {@code simple} configuration, not
     * {@code english}. They have already been stemmed by {@code to_tsvector('english',
     * …)}, and {@code to_tsquery('english', …)} would stem them a second time. English
     * stemming is not idempotent: {@code embedding} becomes {@code embed} and then
     * {@code emb}, {@code database} becomes {@code databas} and then {@code databa},
     * {@code maximumPoolSize} becomes {@code maximumpools} and then {@code maximumpool}.
     * Each of those asks for a lexeme no {@code to_tsvector('english', …)} can produce,
     * so the term matches nothing at all. Measured against this corpus, 53 of 1167
     * distinct indexed lexemes were unreachable that way. {@code simple} does not stem,
     * which leaves each lexeme as the index stored it; the lexemes are quoted because a
     * hyphen or dot inside one is tsquery syntax otherwise.
     */
    private static final String OR_TSQUERY = """
            to_tsquery('simple',
                       nullif((select string_agg(quote_literal(lexeme), ' | ')
                                 from unnest(tsvector_to_array(
                                     to_tsvector('english', :query))) as lexeme), ''))
            """;

    /**
     * Lexical retrieval over the {@code tsv} column, ranked with {@code ts_rank}.
     *
     * <p>Ranked with {@code ts_rank} rather than {@code ts_rank_cd}. Cover density is
     * linear in the number of occurrences — measured on PostgreSQL 17, a lexeme
     * appearing 1, 2, 4, 8, 16 and 64 times scores 0.1, 0.2, 0.4, 0.8, 1.6 and 6.4 — so
     * under the disjunctive query above a chunk matching one query term ten times
     * outranks a chunk matching all three terms once each (1.0 against 0.3).
     * {@code ts_rank} saturates instead, scoring the same pair 0.031 against 0.061,
     * which is the intended order.
     *
     * <p>Neither is Okapi BM25. PostgreSQL's ranking functions are {@code IMMUTABLE} and
     * see no corpus statistics, so there is no IDF term and no length normalisation
     * against a corpus average; {@code ts_rank} supplies only the saturation half of
     * what BM25 does. Fusion consumes ranks rather than scores, so what matters here is
     * the ordering the function produces.
     */
    private List<RetrievedChunk> lexical(UUID ownerId, String query, int limit) {
        return jdbc.sql("""
                select c.id            as chunk_id,
                       c.document_id   as document_id,
                       d.title         as title,
                       c.content       as content,
                       c.char_start    as char_start,
                       c.char_end      as char_end,
                       ts_rank(c.tsv, q.query) as score
                  from chunks c
                  join documents d on d.id = c.document_id,
                       %s as q (query)
                 where c.owner_id = :ownerId
                   and c.tsv @@ q.query
                 order by score desc, c.content_hash
                 limit :limit
                """.formatted(OR_TSQUERY))
                .param("ownerId", ownerId)
                .param("query", query)
                .param("limit", limit)
                .query(RetrievalService::toChunk)
                .list();
    }

    /**
     * Both channels, fused by Reciprocal Rank Fusion.
     *
     * <p>Each channel contributes {@code 1 / (k + rank)} for every chunk it returns, and
     * the sums are ordered descending. A chunk found by both channels outranks one found
     * by either alone, which is the property that makes fusion worth the second query.
     *
     * <p>The constant {@code k} damps the influence of the top few positions. 60 comes
     * from Cormack et al. (2009), where it was fixed during a pilot and never varied —
     * a convention rather than a measured optimum, and one every major engine has since
     * adopted as its default. Bruch et al. (TOIS 2023) find RRF is in fact sensitive to
     * {@code k} and that the best value does not transfer across domains, so it stays
     * configurable and is a candidate for a sweep against the eval set.
     *
     * <p>Both channels run inside one statement so retrieval stays a single round trip.
     * The {@code full outer join} is what admits chunks that only one channel found.
     */
    private List<RetrievedChunk> hybrid(UUID ownerId, String query, int limit) {
        return jdbc.sql("""
                with dense as (
                    select c.id,
                           row_number() over (
                               order by c.embedding <=> :vector, c.content_hash
                           ) as rank
                      from chunks c
                     where c.owner_id = :ownerId
                       and c.embedding is not null
                     order by c.embedding <=> :vector, c.content_hash
                     limit :candidates
                ),
                lexical as (
                    select c.id,
                           row_number() over (
                               order by ts_rank(c.tsv, q.query) desc, c.content_hash
                           ) as rank
                      from chunks c,
                           %s as q (query)
                     where c.owner_id = :ownerId
                       and c.tsv @@ q.query
                     order by ts_rank(c.tsv, q.query) desc, c.content_hash
                     limit :candidates
                ),
                fused as (
                    select coalesce(dense.id, lexical.id) as id,
                           coalesce(1.0 / (:k + dense.rank), 0)
                         + coalesce(1.0 / (:k + lexical.rank), 0) as score
                      from dense
                      full outer join lexical on lexical.id = dense.id
                )
                select c.id            as chunk_id,
                       c.document_id   as document_id,
                       d.title         as title,
                       c.content       as content,
                       c.char_start    as char_start,
                       c.char_end      as char_end,
                       fused.score     as score
                  from fused
                  join chunks c    on c.id = fused.id
                  join documents d on d.id = c.document_id
                 order by fused.score desc, c.content_hash
                 limit :limit
                """.formatted(OR_TSQUERY))
                .param("ownerId", ownerId)
                .param("vector", embed(query))
                .param("query", query)
                .param("candidates", candidateLimit)
                .param("k", rrfK)
                .param("limit", limit)
                .query(RetrievalService::toChunk)
                .list();
    }

    /**
     * Both channels, combined as a weighted sum of min-max normalised scores.
     *
     * <p>Each channel's scores are scaled into [0, 1] across its own candidate list, then
     * summed with weight {@code alpha} on the dense side. Where fusion by rank knows only
     * that one chunk beat another, this keeps the size of the gap: a dense match far ahead
     * of the rest stays far ahead after combining.
     *
     * <p>A channel whose candidates all score identically has no spread to normalise. That
     * collapses to 1.0 for every one of its candidates rather than to a division by zero,
     * which keeps a channel that fired uniformly from silently dropping out.
     */
    private List<RetrievedChunk> convex(UUID ownerId, String query, int limit) {
        return jdbc.sql("""
                with dense as (
                    select c.id, 1 - (c.embedding <=> :vector) as raw
                      from chunks c
                     where c.owner_id = :ownerId
                       and c.embedding is not null
                     order by c.embedding <=> :vector, c.content_hash
                     limit :candidates
                ),
                lexical as (
                    select c.id, ts_rank(c.tsv, q.query) as raw
                      from chunks c,
                           %s as q (query)
                     where c.owner_id = :ownerId
                       and c.tsv @@ q.query
                     order by ts_rank(c.tsv, q.query) desc, c.content_hash
                     limit :candidates
                ),
                dense_scaled as (
                    select id,
                           case when max(raw) over () = min(raw) over () then 1.0
                                else (raw - min(raw) over ())
                                     / (max(raw) over () - min(raw) over ())
                           end as norm
                      from dense
                ),
                lexical_scaled as (
                    select id,
                           case when max(raw) over () = min(raw) over () then 1.0
                                else (raw - min(raw) over ())
                                     / (max(raw) over () - min(raw) over ())
                           end as norm
                      from lexical
                ),
                fused as (
                    select coalesce(d.id, l.id) as id,
                           :alpha * coalesce(d.norm, 0)
                         + (1 - :alpha) * coalesce(l.norm, 0) as score
                      from dense_scaled d
                      full outer join lexical_scaled l on l.id = d.id
                )
                select c.id            as chunk_id,
                       c.document_id   as document_id,
                       d.title         as title,
                       c.content       as content,
                       c.char_start    as char_start,
                       c.char_end      as char_end,
                       fused.score     as score
                  from fused
                  join chunks c    on c.id = fused.id
                  join documents d on d.id = c.document_id
                 order by fused.score desc, c.content_hash
                 limit :limit
                """.formatted(OR_TSQUERY))
                .param("ownerId", ownerId)
                .param("vector", embed(query))
                .param("query", query)
                .param("candidates", candidateLimit)
                .param("alpha", convexAlpha)
                .param("limit", limit)
                .query(RetrievalService::toChunk)
                .list();
    }

    private PGvector embed(String query) {
        return embedTimer.record(() -> new PGvector(embeddings.embedOne(query)));
    }

    private static RetrievedChunk toChunk(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new RetrievedChunk(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                rs.getString("content"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                rs.getDouble("score"));
    }

    /**
     * A retrieved chunk with the span it occupies in its source document, which is what
     * lets an answer cite the exact passage it drew from rather than the document as a
     * whole.
     *
     * <p>{@code score} is comparable within one result set but not across modes: cosine
     * similarity, cover-density rank, and a fused RRF sum are on unrelated scales.
     */
    public record RetrievedChunk(
            UUID chunkId,
            UUID documentId,
            String title,
            String content,
            int charStart,
            int charEnd,
            double score
    ) {
        /** The same chunk carrying a score from a later stage, such as the reranker. */
        RetrievedChunk withScore(double newScore) {
            return new RetrievedChunk(chunkId, documentId, title, content,
                    charStart, charEnd, newScore);
        }
    }
}
