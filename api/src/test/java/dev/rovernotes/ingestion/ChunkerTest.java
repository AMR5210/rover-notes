package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Chunking properties the retrieval layer depends on.
 *
 * <p>Two matter beyond the obvious. Spans must map back to the source text exactly, or
 * a citation built from {@code charStart}/{@code charEnd} highlights the wrong passage.
 * And windows must overlap, or a fact straddling a boundary is retrievable from neither
 * side.
 */
class ChunkerTest {

    private static String words(int count) {
        return IntStream.range(0, count).mapToObj(i -> "word" + i).reduce((a, b) -> a + " " + b).orElse("");
    }

    @Test
    void returnsNothingForBlankInput() {
        Chunker chunker = new Chunker();

        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
    }

    @Test
    void shortTextProducesOneChunk() {
        List<ChunkingStrategy.TextChunk> chunks = new Chunker().chunk("A short note about retrieval.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).isEqualTo("A short note about retrieval.");
        assertThat(chunks.getFirst().ordinal()).isZero();
    }

    @Test
    void longTextIsSplitIntoOrderedChunks() {
        List<ChunkingStrategy.TextChunk> chunks = new Chunker(100, 20).chunk(words(200));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(ChunkingStrategy.TextChunk::ordinal)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void spansMapBackToTheSourceText() {
        String text = words(200);

        for (ChunkingStrategy.TextChunk chunk : new Chunker(100, 20).chunk(text)) {
            assertThat(text.substring(chunk.charStart(), chunk.charEnd()).strip())
                    .isEqualTo(chunk.content());
        }
    }

    @Test
    void consecutiveChunksOverlap() {
        List<ChunkingStrategy.TextChunk> chunks = new Chunker(100, 20).chunk(words(200));

        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).charStart())
                    .as("chunk %d should start before chunk %d ends", i, i - 1)
                    .isLessThan(chunks.get(i - 1).charEnd());
        }
    }

    @Test
    void doesNotSplitWordsAcrossChunks() {
        for (ChunkingStrategy.TextChunk chunk : new Chunker(100, 20).chunk(words(200))) {
            for (String token : chunk.content().split(" ")) {
                assertThat(token).matches("word\\d+");
            }
        }
    }

    @Test
    void alwaysMakesProgressOnTextWithoutSpaces() {
        // A single very long token cannot be snapped to a word boundary. The chunker
        // must still advance rather than loop forever.
        String noSpaces = "x".repeat(1000);

        List<ChunkingStrategy.TextChunk> chunks = new Chunker(100, 20).chunk(noSpaces);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getLast().charEnd()).isEqualTo(1000);
    }

    @Test
    void rejectsInvalidWindowConfiguration() {
        assertThatThrownBy(() -> new Chunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
