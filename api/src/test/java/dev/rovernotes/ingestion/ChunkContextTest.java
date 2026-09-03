package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What gets embedded when chunk context is on.
 *
 * <p>The property that matters most is the one about what is <em>not</em> changed: the
 * prefix must never reach the stored content, because an answer cites a passage by
 * character offset into its source document.
 */
class ChunkContextTest {

    private static final String DOCUMENT = """
            # Performance tuning guide

            Where the time goes.

            ## Sizing the connection pool

            The useful bound is the number of cores times a small factor.

            ## Batch sizes

            Every call has a fixed overhead.
            """;

    private static ChunkingStrategy.TextChunk chunkAt(String needle) {
        int start = DOCUMENT.indexOf(needle);
        return new ChunkingStrategy.TextChunk(0, needle, start, start + needle.length());
    }

    @Test
    void noneEmbedsTheChunkAlone() {
        ChunkContext context = new ChunkContext(ChunkContext.Mode.NONE);
        var chunk = chunkAt("The useful bound is the number of cores times a small factor.");

        assertThat(context.forEmbedding("guide-performance-tuning", DOCUMENT, chunk))
                .isEqualTo(chunk.content());
    }

    @Test
    void headingPrefixesTheTitleAndEnclosingSection() {
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        var chunk = chunkAt("The useful bound is the number of cores times a small factor.");

        assertThat(context.forEmbedding("guide-performance-tuning", DOCUMENT, chunk))
                .isEqualTo("guide performance tuning — Sizing the connection pool\n\n"
                        + chunk.content());
    }

    @Test
    void takesTheSectionTheChunkStartsInsideNotTheNextOne() {
        // Searching forwards would label a chunk with the heading it happens to run into,
        // which is the section it is not part of.
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        var chunk = chunkAt("Every call has a fixed overhead.");

        assertThat(context.forEmbedding("guide-performance-tuning", DOCUMENT, chunk))
                .startsWith("guide performance tuning — Batch sizes\n\n");
    }

    @Test
    void aChunkBeforeAnySectionTakesTheTitleAlone() {
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        var chunk = chunkAt("Where the time goes.");

        assertThat(context.forEmbedding("guide-performance-tuning", DOCUMENT, chunk))
                .isEqualTo("guide performance tuning\n\n" + chunk.content());
    }

    @Test
    void readsASlugAsWords() {
        // The title is a filename stem. Left hyphenated it is one token the model has
        // never seen; as words it is three the model knows.
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        var chunk = chunkAt("Where the time goes.");

        assertThat(context.forEmbedding("runbook-model-upgrades", DOCUMENT, chunk))
                .startsWith("runbook model upgrades");
    }

    @Test
    void aDocumentWithNoSectionsStillGetsItsTitle() {
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        String flat = "# Connection pooling\n\nThe pool is sized against the database.";
        var chunk = new ChunkingStrategy.TextChunk(0, "The pool is sized against the database.",
                flat.indexOf("The pool"), flat.length());

        assertThat(context.forEmbedding("connection-pooling", flat, chunk))
                .isEqualTo("connection pooling\n\nThe pool is sized against the database.");
    }

    @Test
    void aMissingTitleLeavesTheChunkUsable() {
        ChunkContext context = new ChunkContext(ChunkContext.Mode.HEADING);
        var chunk = chunkAt("Every call has a fixed overhead.");

        assertThat(context.forEmbedding(null, DOCUMENT, chunk))
                .isEqualTo("Batch sizes\n\n" + chunk.content());
        assertThat(context.forEmbedding("  ", null, chunk)).isEqualTo(chunk.content());
    }
}
