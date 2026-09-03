package dev.rovernotes.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.pgvector.PGvector;
import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.Mockito;

/**
 * Exercises the three retrieval modes against a real pgvector database.
 *
 * <p>The embedding service is stubbed so the fixtures state their own vectors. That keeps
 * the assertions about the SQL — ordering, owner isolation, and fusion — rather than
 * about what a particular model happens to encode. Retrieval quality against real
 * embeddings is measured separately by the eval harness in {@code evals/}.
 */
@SpringBootTest
@ActiveProfiles("local")
class RetrievalIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    RetrievalService retrieval;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    EmbeddingClient embeddings;

    @MockitoBean
    RerankClient reranker;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        jdbc.sql("delete from chunks").update();
        jdbc.sql("delete from documents").update();
        // owner_id is a foreign key from V3, so both owners have to be real accounts.
        dev.rovernotes.TestAccounts.create(jdbc, OWNER);
        dev.rovernotes.TestAccounts.create(jdbc, STRANGER);

        UUID doc = insertDocument(OWNER, "Retrieval notes");
        insertChunk(doc, OWNER, 0, "RRF fuses ranked lists", topical(1.0f));
        insertChunk(doc, OWNER, 1, "Cross-encoders rerank results", topical(0.6f));
        insertChunk(insertDocument(OWNER, "Deployment notes"), OWNER, 0,
                "Terraform provisions ECS", topical(-1.0f));

        insertChunk(doc, STRANGER, 2, "RRF fuses ranked lists for another owner", topical(1.0f));

        // Every query embeds to the same "topical" direction as the first chunk, which
        // makes the dense ordering deterministic and independent of any real model.
        Mockito.when(embeddings.embedOne(Mockito.anyString())).thenReturn(topical(1.0f));
        // The reranker's own behaviour is covered by RerankClientTest against a real HTTP
        // server; here it only needs to record whether it was asked.
        Mockito.when(reranker.rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt()))
                .thenAnswer(call -> call.getArgument(1));
    }

    @Test
    void denseRanksByCosineDistance() {
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "anything", 10, RetrievalMode.DENSE, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("RRF fuses ranked lists",
                        "Cross-encoders rerank results",
                        "Terraform provisions ECS");
    }

    @Test
    void lexicalReturnsOnlyChunksMatchingTheQueryTerms() {
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "ranked lists", 10, RetrievalMode.LEXICAL, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("RRF fuses ranked lists");
    }

    @Test
    void lexicalMatchesAnyTermRatherThanRequiringAll() {
        // Conjunctive parsing would need every term in one chunk and return nothing here.
        // Matching on any term is what lets a question phrased as a sentence retrieve the
        // passage that answers it; ranking and fusion supply the precision.
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "How do ranked lists get combined?", 10, RetrievalMode.LEXICAL, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .contains("RRF fuses ranked lists");
    }

    @Test
    void lexicalToleratesPunctuationThatWouldBreakToTsquery() {
        // Passed straight to to_tsquery, this raises a syntax error and turns a harmless
        // query into a 500. Normalising through to_tsvector first cannot raise.
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "ranked lists &|!() ", 10, RetrievalMode.LEXICAL, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("RRF fuses ranked lists");
    }

    @Test
    void lexicalMatchesTermsWhoseStemIsNotIdempotent() {
        // The chunk says "embeddings"; to_tsvector('english', …) indexes that as `embed`.
        // Building the query with to_tsquery('english', 'embed') stems a second time to
        // `emb`, which no tsvector contains, so the term silently matches nothing. 53 of
        // this corpus's 1167 indexed lexemes were unreachable that way, `database` and
        // `dimension` among them.
        UUID doc = insertDocument(OWNER, "Embedding notes");
        insertChunk(doc, OWNER, 0, "Embeddings are compared by cosine distance", topical(0.5f));

        assertThat(retrieval.search(OWNER, "embedding", 10, RetrievalMode.LEXICAL, false))
                .extracting(RetrievalService.RetrievedChunk::content)
                .containsExactly("Embeddings are compared by cosine distance");
    }

    @Test
    void lexicalReturnsNothingWhenTheQueryHasNoLexemes() {
        // Stop words and punctuation leave an empty tsquery. Matching nothing is the
        // intended answer; the alternative is an error from an empty-string cast.
        assertThat(retrieval.search(OWNER, "the and of", 10, RetrievalMode.LEXICAL, false)).isEmpty();
        assertThat(retrieval.search(OWNER, "!!! ???", 10, RetrievalMode.LEXICAL, false)).isEmpty();
    }

    @Test
    void fusionPromotesAChunkFoundByBothChannels() {
        // Dense alone ranks "RRF fuses ranked lists" first. The lexical query matches only
        // "Cross-encoders rerank results", so appearing in both lists is what lifts it to
        // the top — the property that makes the second query worth running.
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "rerank results", 10, RetrievalMode.HYBRID, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .startsWith("Cross-encoders rerank results");
    }

    @Test
    void fusionKeepsChunksOnlyOneChannelFound() {
        List<RetrievalService.RetrievedChunk> hits =
                retrieval.search(OWNER, "rerank results", 10, RetrievalMode.HYBRID, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactlyInAnyOrder("RRF fuses ranked lists",
                        "Cross-encoders rerank results",
                        "Terraform provisions ECS");
    }

    @Test
    void convexFusionKeepsChunksOnlyOneChannelFound() {
        // The full outer join matters as much here as under rank fusion: a chunk the
        // lexical query never matched still carries its dense score.
        List<RetrievalService.RetrievedChunk> hits = convexRetrieval()
                .search(OWNER, "rerank results", 10, RetrievalMode.HYBRID, false);

        assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                .containsExactlyInAnyOrder("RRF fuses ranked lists",
                        "Cross-encoders rerank results",
                        "Terraform provisions ECS");
    }

    @Test
    void convexFusionScoresStayWithinTheUnitInterval() {
        // alpha * dense + (1 - alpha) * lexical over two normalised channels cannot leave
        // [0, 1]. A score outside it means a normalisation divided by the wrong spread.
        List<RetrievalService.RetrievedChunk> hits = convexRetrieval()
                .search(OWNER, "ranked lists", 10, RetrievalMode.HYBRID, false);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit ->
                assertThat(hit.score()).isBetween(0.0, 1.0));
    }

    @Test
    void everyModeIsolatesOwners() {
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<RetrievalService.RetrievedChunk> hits =
                    retrieval.search(STRANGER, "ranked lists", 10, mode, false);

            assertThat(hits).extracting(RetrievalService.RetrievedChunk::content)
                    .as("mode %s", mode)
                    .allMatch(content -> content.endsWith("another owner"));
        }
    }

    @Test
    void rerankCanBeRequestedEvenWhenItIsNotTheDefault() {
        // rover.retrieval.rerank-enabled is false under the local profile. An explicit
        // request must still reach the reranker, or the eval harness cannot score the
        // stage on and off against one running instance — and the search response would
        // report reranked=true while quietly serving the fused order.
        assertThat(retrieval.rerankByDefault()).isFalse();

        retrieval.search(OWNER, "ranked lists", 10, RetrievalMode.HYBRID, true);

        Mockito.verify(reranker).rerank(Mockito.eq("ranked lists"), Mockito.anyList(), Mockito.eq(10));
    }

    @Test
    void rerankIsNotCalledWhenTheCallerDeclinesIt() {
        retrieval.search(OWNER, "ranked lists", 10, RetrievalMode.HYBRID, false);

        Mockito.verify(reranker, Mockito.never())
                .rerank(Mockito.anyString(), Mockito.anyList(), Mockito.anyInt());
    }

    @Test
    void anIdentifierQueryRoutesToTheLexicalChannel() {
        assertThat(retrieval.routeByDefault()).isTrue();
        assertThat(retrieval.modeFor("event_publication", true)).isEqualTo(RetrievalMode.LEXICAL);
    }

    @Test
    void aQuestionKeepsTheConfiguredMode() {
        // The router's cost is entirely in what it fires on wrongly, so this is the
        // assertion that matters: a question must reach fusion unchanged.
        assertThat(retrieval.modeFor("How are ranked lists combined?", true))
                .isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void routingCanBeDeclinedForOneQuery() {
        assertThat(retrieval.modeFor("event_publication", false)).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void routingFallsBackWhenTheLexicalChannelFindsNothing() {
        // PostgreSQL keeps some compound tokens whole, so a query for one fragment
        // matches no lexeme and the lexical channel is empty. Routing must not turn a
        // result the fused ranking would have found into no result at all.
        UUID doc = insertDocument(OWNER, "Model notes");
        insertChunk(doc, OWNER, 0, "Embeddings come from BAAI/bge-small-en-v1.5", topical(0.9f));

        assertThat(retrieval.search(OWNER, "BAAI", 10, RetrievalMode.LEXICAL, false)).isEmpty();

        RetrievalService.Result routed =
                retrieval.routedSearch(OWNER, "BAAI", 10, null, false, true);

        assertThat(routed.mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(routed.hits()).isNotEmpty();
    }

    @Test
    void aRoutedQueryThatFindsResultsKeepsTheRoutedChannel() {
        RetrievalService.Result routed =
                retrieval.routedSearch(OWNER, "RRF", 10, null, false, true);

        assertThat(routed.mode()).isEqualTo(RetrievalMode.HYBRID);

        UUID doc = insertDocument(OWNER, "Config notes");
        insertChunk(doc, OWNER, 0, "The event_publication table is the outbox", topical(0.2f));

        RetrievalService.Result hit =
                retrieval.routedSearch(OWNER, "event_publication", 10, null, false, true);

        assertThat(hit.mode()).isEqualTo(RetrievalMode.LEXICAL);
        assertThat(hit.hits()).isNotEmpty();
    }

    @Test
    void anExplicitModeSuppressesRoutingAndItsFallback() {
        RetrievalService.Result result =
                retrieval.routedSearch(OWNER, "event_publication", 10, RetrievalMode.DENSE,
                        false, true);

        assertThat(result.mode()).isEqualTo(RetrievalMode.DENSE);
    }

    @Test
    void answersFromTheLexicalChannelWhenEmbeddingFails() {
        // Both channels that need an embedding are unavailable when the embedding server
        // is; the lexical one is not. A worse ranking beats no search at all.
        Mockito.when(embeddings.embedOne(Mockito.anyString()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        RetrievalService.Result result =
                retrieval.routedSearch(OWNER, "ranked lists", 10, null, false, false);

        assertThat(result.hits()).isNotEmpty();
        // Reported, not silent: the caller can see which channel actually answered.
        assertThat(result.mode()).isEqualTo(RetrievalMode.LEXICAL);
    }

    @Test
    void anExplicitDenseRequestStillFailsWhenEmbeddingDoes() {
        // Naming a channel is an instruction. Quietly answering from a different one
        // would make the eval harness's per-channel runs measure the wrong thing.
        Mockito.when(embeddings.embedOne(Mockito.anyString()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> retrieval.routedSearch(
                OWNER, "ranked lists", 10, RetrievalMode.DENSE, false, false))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
    }

    @Test
    void blankQueryReturnsNothingWithoutTouchingTheDatabase() {
        assertThat(retrieval.search(OWNER, "   ", 10, RetrievalMode.HYBRID, false)).isEmpty();
        Mockito.verify(embeddings, Mockito.never()).embedOne("   ");
    }

    /** A second service bound to convex fusion, so both strategies are covered here. */
    private RetrievalService convexRetrieval() {
        return new RetrievalService(jdbc, embeddings, reranker, new QueryRouter(), false,
                RetrievalMode.HYBRID, false,
                10, 100, 40, 60, 100, FusionStrategy.CONVEX, 0.8, transactionManager,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private UUID insertDocument(UUID owner, String title) {
        return jdbc.sql("""
                insert into documents (owner_id, title, content, content_hash)
                values (:owner, :title, 'body', :hash)
                returning id
                """)
                .param("owner", owner)
                .param("title", title)
                .param("hash", UUID.randomUUID().toString())
                .query(UUID.class)
                .single();
    }

    private void insertChunk(UUID document, UUID owner, int ordinal, String content, float[] vector) {
        jdbc.sql("""
                insert into chunks (document_id, owner_id, ordinal, content, content_hash,
                                    char_start, char_end, embedding)
                values (:document, :owner, :ordinal, :content, :hash, 0, :end, :embedding)
                """)
                .param("document", document)
                .param("owner", owner)
                .param("ordinal", ordinal)
                .param("content", content)
                .param("hash", UUID.randomUUID().toString())
                .param("end", content.length())
                .param("embedding", new PGvector(vector))
                .update();
    }

    /** A 384-dimension vector whose first component carries the whole signal. */
    private static float[] topical(float signal) {
        float[] vector = new float[384];
        java.util.Arrays.fill(vector, 0.01f);
        vector[0] = signal;
        return vector;
    }
}
