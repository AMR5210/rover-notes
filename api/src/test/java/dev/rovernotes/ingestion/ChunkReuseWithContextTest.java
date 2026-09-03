package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.notes.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Reuse when the embedded text is not the chunk's own text.
 *
 * <p>With {@code chunk-context: heading} a chunk is embedded prefixed by its document
 * title and section heading, so two things that {@link ChunkReuseTest} cannot separate
 * come apart: what a chunk contains, and what produced its vector. Renaming a document
 * changes every vector while leaving every chunk's content byte-identical.
 *
 * <p>That is why the reuse key is a hash of the string handed to the embedding model
 * rather than the {@code content_hash} the schema already carried. Under the committed
 * default the two are the same value, so this is the only place the distinction is
 * visible — and getting it wrong is silent: the document keeps vectors computed from its
 * old title, and nothing about the row says so.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "rover.ingestion.chunk-context=heading")
class ChunkReuseWithContextTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static String body() {
        StringBuilder text = new StringBuilder("# Fusion\n\n");
        while (text.length() < 2200) {
            text.append("Retrieval fuses ranked lists and reranks the result. ");
        }
        return text.toString();
    }

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ChunkRepository chunks;

    @MockitoBean
    EmbeddingClient embeddings;

    private final List<String> embeddedTexts = new CopyOnWriteArrayList<>();

    private UUID owner;

    @BeforeEach
    void stubEmbeddings() {
        embeddedTexts.clear();
        Mockito.when(embeddings.embed(Mockito.anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            embeddedTexts.addAll(texts);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] vector = new float[384];
                vector[Math.floorMod(text.hashCode(), 384)] = 1f;
                vectors.add(vector);
            }
            return vectors;
        });
        owner = TestAccounts.create(jdbc);
    }

    @Test
    void theTitleReachesTheEmbeddingButNotTheStoredContent() {
        // The premise the test below rests on. Asserted rather than assumed, because if
        // the prefix were not applied the reuse decision would be correct by accident.
        var document = notes.create(owner, "Retrieval", body());
        awaitIndexed(document.id());

        assertThat(embeddedTexts).isNotEmpty();
        assertThat(embeddedTexts).allSatisfy(text ->
                assertThat(text).startsWithIgnoringCase("Retrieval"));
        // The stored span is what a citation resolves against, so it must not carry the
        // prefix that only the embedding sees.
        assertThat(storedContent(document.id())).allSatisfy(content ->
                assertThat(content).doesNotStartWithIgnoringCase("Retrieval\n"));
    }

    @Test
    void renamingADocumentReembedsEveryChunk() {
        // Keyed on content_hash this would reuse every vector, and the document would
        // keep embeddings computed from a title it no longer has.
        var document = notes.create(owner, "Retrieval", body());
        awaitIndexed(document.id());
        int chunkCount = chunks.countByDocument(document.id());
        assertThat(chunkCount).isGreaterThan(1);

        embeddedTexts.clear();
        notes.update(owner, document.id(), "Ranking and fusion", body());
        awaitIndexed(document.id());

        assertThat(embeddedTexts).hasSize(chunkCount);
        assertThat(embeddedTexts).allSatisfy(text ->
                assertThat(text).startsWithIgnoringCase("Ranking and fusion"));
    }

    @Test
    void reindexingUnderTheSameTitleReusesEveryChunk() {
        // The other half: the prefix is part of the key, so an unchanged prefix over
        // unchanged text still costs nothing.
        var document = notes.create(owner, "Retrieval", body());
        awaitIndexed(document.id());
        int chunkCount = chunks.countByDocument(document.id());

        embeddedTexts.clear();
        notes.update(owner, document.id(), "Retrieval", body() + "​");
        awaitIndexed(document.id());

        // A zero-width character on the end changes the document, so indexing runs, and
        // lands inside the final window only.
        assertThat(embeddedTexts).hasSizeLessThan(chunkCount);
    }

    private void awaitIndexed(UUID documentId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from event_publication
                                 where completion_date is null
                                   and serialized_event like :pattern
                                """)
                        .param("pattern", "%" + documentId + "%")
                        .query(Long.class)
                        .single()).isZero());
    }

    private List<String> storedContent(UUID documentId) {
        return jdbc.sql("select content from chunks where document_id = :id order by ordinal")
                .param("id", documentId)
                .query(String.class)
                .list();
    }
}
