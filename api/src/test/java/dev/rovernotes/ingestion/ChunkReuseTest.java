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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What a re-index pays for.
 *
 * <p>The schema has carried a chunk hash since the first migration as the key that lets an
 * edit re-embed part of a document rather than all of it. Indexing did not consult it:
 * every change deleted the document's chunks and embedded the whole thing again. These
 * tests are what the claim now rests on, and they assert on the texts sent to the
 * embedding client rather than on row counts — counting rows would pass equally well
 * against the behaviour being replaced, because the row count is the same either way.
 *
 * <h2>What the committed chunker allows</h2>
 *
 * <p>The default is a fixed 1,600-character window with 200 characters of overlap, so a
 * chunk's text depends on where every preceding character put the boundary. Reuse is
 * therefore whole where a document is re-imported unchanged or grown at the end, and
 * partial where an edit changes the length of something ahead of other chunks. The last
 * case is measured here rather than left implied: it is a property of fixed-window
 * chunking, not of the reuse, and boundary-aligned chunking is what would change it.
 */
@SpringBootTest
@ActiveProfiles("local")
class ChunkReuseTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /**
     * A paragraph of about {@code chars} characters, tagged so two of them differ.
     *
     * <p>Sized so that four make a document of roughly 4,000 characters, which the
     * committed window splits into three chunks. A document below the window size is one
     * chunk and could not tell whole reuse from partial.
     */
    private static String paragraph(String tag, int chars) {
        StringBuilder text = new StringBuilder();
        while (text.length() < chars) {
            text.append(tag).append(" retrieval fuses ranked lists and reranks them. ");
        }
        return text.toString();
    }

    private static String document(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }

    private static String baseDocument() {
        return document(paragraph("Alpha", 1000), paragraph("Beta", 1000),
                paragraph("Gamma", 1000), paragraph("Delta", 1000));
    }

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ChunkRepository chunks;

    /**
     * Records what it was asked to embed, and answers deterministically.
     *
     * <p>The vector is derived from the text, so a chunk that was re-embedded is
     * indistinguishable in the row from one that was not. Only the recording separates
     * them, which is the point: the saving is in the call, not in the data.
     */
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
    void anIdenticalReimportNeverReachesIndexing() {
        // The document-level guard, which predates chunk reuse and sits in front of it:
        // NoteService compares the stored content hash and publishes no event at all.
        // Asserted here so the chunk-level tests below are not mistaken for the thing
        // that makes re-importing a file free — this is.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        embeddedTexts.clear();

        long before = publicationsFor(document.id());
        notes.update(owner, document.id(), "Retrieval", baseDocument());

        // No new publication at all, rather than one that indexed cheaply.
        assertThat(publicationsFor(document.id())).isEqualTo(before);
        assertThat(embeddedTexts).isEmpty();
    }

    @Test
    void renamingADocumentReusesEveryChunk() {
        // A rename does reach indexing: the title is part of the document, so the event
        // fires and every chunk is offered for embedding again. With chunk context off
        // the embedding input is the chunk's own text, which has not changed, so nothing
        // is recomputed. This is the case the reuse exists for.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        assertThat(embeddedTexts).hasSize(3);

        embeddedTexts.clear();
        notes.update(owner, document.id(), "Retrieval, revisited", baseDocument());
        awaitIndexed(document.id());

        assertThat(embeddedTexts).isEmpty();
        assertThat(chunks.countByDocument(document.id())).isEqualTo(3);
    }

    @Test
    void aChunkKeepsTheRowItAlreadyOccupied() {
        // The saving is that the vector is never rewritten, which is only true if the row
        // survives. Identifiers are the evidence: delete-and-reinsert would produce new
        // ones while every other assertion here still passed.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        var before = idsFor(document.id());

        notes.update(owner, document.id(), "Retrieval, revisited", baseDocument());
        awaitIndexed(document.id());

        assertThat(idsFor(document.id())).containsExactlyElementsOf(before);
    }

    @Test
    void appendingAParagraphEmbedsOnlyWhatTheTailChanged() {
        // Growing a document at the end is the common case for an ingest pipeline, and
        // the one where a fixed window keeps most of its boundaries: everything before
        // the old end is split exactly as it was.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        embeddedTexts.clear();

        notes.update(owner, document.id(), "Retrieval",
                baseDocument() + "\n\n" + paragraph("Epsilon", 1000));
        awaitIndexed(document.id());

        assertThat(chunks.countByDocument(document.id())).isEqualTo(4);
        assertThat(embeddedTexts).hasSize(2);
        assertThat(ordinalsFor(document.id())).containsExactly(0, 1, 2, 3);
    }

    @Test
    void anEditThatKeepsItsLengthEmbedsOnlyTheChunkItFallsIn() {
        // Same length, so no boundary after it moves and only the window containing the
        // change has different text.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        embeddedTexts.clear();

        notes.update(owner, document.id(), "Retrieval",
                document(paragraph("Alpha", 1000), paragraph("Beta", 1000),
                        paragraph("Gamma", 1000), paragraph("Omega", 1000)));
        awaitIndexed(document.id());

        assertThat(embeddedTexts).hasSize(1);
        assertThat(chunks.countByDocument(document.id())).isEqualTo(3);
    }

    @Test
    void insertingAtTheStartShiftsEveryWindowAndEmbedsAgain() {
        // The limit of hashing a fixed window, recorded rather than implied. Thirty
        // characters at the front move every boundary after them, so no chunk's text
        // survives and the document is embedded in full. Boundary-aligned chunking is
        // what would make this edit local; the hash cannot, because there is genuinely no
        // chunk whose text is unchanged.
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        embeddedTexts.clear();

        notes.update(owner, document.id(), "Retrieval",
                "A short new opening sentence.\n\n" + baseDocument());
        awaitIndexed(document.id());

        assertThat(embeddedTexts).hasSize(3);
        assertThat(chunks.countByDocument(document.id())).isEqualTo(3);
    }

    @Test
    void shrinkingADocumentDropsTheRowsItNoLongerFills() {
        var document = notes.create(owner, "Retrieval", baseDocument());
        awaitChunks(document.id(), 3);
        embeddedTexts.clear();

        notes.update(owner, document.id(), "Retrieval", paragraph("Alpha", 1000));
        awaitIndexed(document.id());

        assertThat(chunks.countByDocument(document.id())).isEqualTo(1);
        // Contiguous from zero, so the rows that are gone are gone rather than left
        // behind holding ordinals nothing points at.
        assertThat(ordinalsFor(document.id())).containsExactly(0);
    }

    @Test
    void twoChunksWithTheSameTextClaimTwoRows() {
        // Reuse claims one row per occurrence rather than looking a hash up. Keyed on the
        // hash alone, a document containing the same passage twice would have its second
        // occurrence find the first one's row already taken — and either re-embed it or,
        // worse, share the row and leave the document a chunk short.
        UUID documentId = seedTwoIdenticalChunks();

        var reusable = chunks.reusableByHash(documentId);

        assertThat(reusable).hasSize(1);
        assertThat(reusable.values().iterator().next()).hasSize(2);
    }

    /** Writes two rows for one document whose embedding input is byte-identical. */
    private UUID seedTwoIdenticalChunks() {
        var document = notes.create(owner, "Repeated", paragraph("Alpha", 1000));
        awaitChunks(document.id(), 1);

        var repeated = new ChunkRepository.StoredChunk(
                1, "a repeated passage", null, "content-hash", "same-embedding-hash",
                4, 0, 18, new float[384]);
        chunks.insert(document.id(), owner, repeated);
        chunks.insert(document.id(), owner,
                new ChunkRepository.StoredChunk(
                        2, repeated.content(), repeated.contextualizedContent(),
                        repeated.contentHash(), repeated.embeddingHash(),
                        repeated.tokenCount(), repeated.charStart(), repeated.charEnd(),
                        new float[384]));

        // The document's own chunk carries a different hash, so the assertion above is
        // about the repeated pair rather than about everything in the document.
        jdbc.sql("delete from chunks where document_id = :id and ordinal = 0")
                .param("id", document.id())
                .update();
        return document.id();
    }

    private void awaitChunks(UUID documentId, int expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() ->
                        assertThat(chunks.countByDocument(documentId)).isEqualTo(expected));
    }

    /**
     * Waits until this document's own indexing has completed.
     *
     * <p>Scoped to the document rather than to the table. A wait on "nothing outstanding
     * anywhere" makes every test in the class depend on every other one having indexed
     * cleanly, so a single document that fails to index turns one assertion failure into
     * a timeout in every test that runs after it.
     */
    private void awaitIndexed(UUID documentId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(incompleteFor(documentId)).isZero());
    }

    private Long incompleteFor(UUID documentId) {
        return jdbc.sql("""
                        select count(*) from event_publication
                         where completion_date is null
                           and serialized_event like :pattern
                        """)
                .param("pattern", "%" + documentId + "%")
                .query(Long.class)
                .single();
    }

    private long publicationsFor(UUID documentId) {
        return jdbc.sql("""
                        select count(*) from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + documentId + "%")
                .query(Long.class)
                .single();
    }

    private List<Integer> ordinalsFor(UUID documentId) {
        return jdbc.sql("select ordinal from chunks where document_id = :id order by ordinal")
                .param("id", documentId)
                .query(Integer.class)
                .list();
    }

    private List<UUID> idsFor(UUID documentId) {
        return jdbc.sql("select id from chunks where document_id = :id order by ordinal")
                .param("id", documentId)
                .query(UUID.class)
                .list();
    }
}
