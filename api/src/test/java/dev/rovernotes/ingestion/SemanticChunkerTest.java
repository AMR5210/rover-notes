package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.rovernotes.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Splitting behaviour, with the embedding model stubbed.
 *
 * <p>Each fixture states its own vectors, so the assertions are about where the chunker
 * cuts given a distance curve rather than about what a particular model encodes. Whether
 * real embeddings put the boundaries in useful places is a retrieval-quality question,
 * measured by the eval harness and recorded in {@code `docs/RESULTS.md`}.
 */
class SemanticChunkerTest {

    private static final Map<String, float[]> DIRECTIONS = Map.of(
            "A", vector(1, 0, 0),
            "B", vector(0, 1, 0),
            "C", vector(0, 0, 1));

    /** Embeds each sentence as the direction named by its first character. */
    private static EmbeddingClient topicEmbedder() {
        EmbeddingClient embeddings = Mockito.mock(EmbeddingClient.class);
        Mockito.when(embeddings.embed(Mockito.anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                out.add(DIRECTIONS.getOrDefault(text.strip().substring(0, 1), vector(1, 1, 1)));
            }
            return out;
        });
        return embeddings;
    }

    private static float[] vector(float x, float y, float z) {
        return new float[] {x, y, z};
    }

    private SemanticChunker chunker(double percentile, int min, int max) {
        return new SemanticChunker(topicEmbedder(), percentile, min, max);
    }

    /** A sentence of roughly {@code chars} characters beginning with {@code topic}. */
    private static String sentence(String topic, int chars) {
        return topic + " " + "word ".repeat(Math.max(1, chars / 5)) + "end.";
    }

    @Test
    void cutsWhereTheSubjectChanges() {
        // Three sentences on topic A, then three on topic B. The only large distance in
        // the document is at the A-to-B transition, so that is where the cut belongs.
        String text = sentence("A", 300) + " " + sentence("A", 300) + " " + sentence("A", 300)
                + " " + sentence("B", 300) + " " + sentence("B", 300) + " " + sentence("B", 300);

        List<ChunkingStrategy.TextChunk> chunks = chunker(95, 100, 1000).chunk(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).startsWith("A").doesNotContain("B word");
        assertThat(chunks.get(1).content()).startsWith("B");
    }

    @Test
    void leavesADocumentThatAlreadyFitsInOneChunkAlone() {
        // The percentile always finds a largest distance, so without this a short note is
        // cut at whichever sentence pair happens to differ most and each half carries
        // less context than the whole did. Fixed windows leave a document under the
        // window size whole, and the two strategies have to agree here to be comparable.
        String text = sentence("A", 300) + " " + sentence("B", 300);

        assertThat(chunker(95, 100, 5000).chunk(text)).hasSize(1);
        assertThat(chunker(95, 100, 400).chunk(text)).hasSizeGreaterThan(1);
    }

    @Test
    void aUniformDocumentIsCutOnlyAsMuchAsItsLengthDemands() {
        // Every sentence is on the same topic, so no distance exceeds the threshold and
        // nothing is cut for semantic reasons. What splits this is the maximum length,
        // and the result should be the fewest pieces that respects it rather than one
        // per sentence.
        String text = (sentence("A", 200) + " ").repeat(6);

        List<ChunkingStrategy.TextChunk> chunks = chunker(95, 100, 700).chunk(text);

        assertThat(chunks).hasSizeBetween(2, 3);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(700));
    }

    @Test
    void spansCoverTheDocumentInOrderAndDoNotOverlap() {
        // Fixed windows overlap deliberately; these must not, or the same text is
        // indexed twice and a citation span becomes ambiguous.
        String text = sentence("A", 300) + " " + sentence("B", 300) + " " + sentence("C", 300);

        List<ChunkingStrategy.TextChunk> chunks = chunker(50, 100, 400).chunk(text);

        assertThat(chunks).isNotEmpty();
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).ordinal()).isEqualTo(i);
            assertThat(chunks.get(i).charStart()).isLessThan(chunks.get(i).charEnd());
            if (i > 0) {
                assertThat(chunks.get(i).charStart())
                        .as("chunk %d starts at or after the end of chunk %d", i, i - 1)
                        .isGreaterThanOrEqualTo(chunks.get(i - 1).charEnd());
            }
        }
    }

    @Test
    void everySpanQuotesItsSourceExactly() {
        // The span offsets are what lets an answer cite the passage it used, so a chunk
        // whose content does not match its own span would produce a wrong citation.
        String text = sentence("A", 300) + " " + sentence("B", 300) + " " + sentence("C", 300);

        for (ChunkingStrategy.TextChunk chunk : chunker(50, 100, 400).chunk(text)) {
            assertThat(text.substring(chunk.charStart(), chunk.charEnd()).strip())
                    .isEqualTo(chunk.content());
        }
    }

    @Test
    void mergesAPieceBelowTheMinimum() {
        // A one-sentence topic shift should not become a chunk too short to embed
        // meaningfully; it belongs with its neighbour.
        String text = sentence("A", 600) + " " + sentence("B", 20) + " " + sentence("A", 600);

        List<ChunkingStrategy.TextChunk> chunks = chunker(50, 400, 800).chunk(text);

        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isGreaterThanOrEqualTo(400));
    }

    @Test
    void splitsAPieceAboveTheMaximum() {
        // A uniform document has no distance that clears the threshold, so without the
        // maximum it would collapse into one chunk and the split would buy nothing.
        String text = (sentence("A", 400) + " ").repeat(8);

        List<ChunkingStrategy.TextChunk> chunks = chunker(95, 300, 1200).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(1200));
    }

    @Test
    void aSingleLongSentenceIsEmittedRatherThanCutMidSentence() {
        // There is no interior boundary to cut on. Emitting it over-length is better
        // than splitting mid-sentence, which is the failure fixed windows have.
        String text = sentence("A", 4000);

        List<ChunkingStrategy.TextChunk> chunks = chunker(95, 300, 1000).chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text.strip());
    }

    @Test
    void blankInputProducesNoChunks() {
        assertThat(chunker(95, 300, 2400).chunk(null)).isEmpty();
        assertThat(chunker(95, 300, 2400).chunk("   \n\n ")).isEmpty();
    }

    @Test
    void aSingleSentenceNeedsNoEmbedding() {
        assertThat(chunker(95, 300, 2400).chunk("Just the one sentence."))
                .singleElement()
                .satisfies(chunk -> assertThat(chunk.content()).isEqualTo("Just the one sentence."));
    }

    @Test
    void percentileInterpolatesRatherThanPickingAnObservedValue() {
        // Nearest-rank would return 4.0 here, and `distance > threshold` would then never
        // cut at the most distant pair — the one cut the document most needs.
        double[] values = {1.0, 2.0, 3.0, 4.0};

        assertThat(SemanticChunker.percentile(values, 50)).isEqualTo(2.5);
        assertThat(SemanticChunker.percentile(values, 95)).isLessThan(4.0);
        assertThat(SemanticChunker.percentile(new double[] {7.0}, 95)).isEqualTo(7.0);
    }

    @Test
    void aZeroVectorIsNotReadAsATopicChange() {
        // An empty or degenerate embedding has no direction. Scoring it as maximally
        // distant would cut the document at whatever the model failed on.
        assertThat(SemanticChunker.cosine(new float[] {0, 0, 0}, vector(1, 0, 0))).isEqualTo(1.0);
    }

    @Test
    void rejectsBoundsThatCannotBeSatisfied() {
        assertThatThrownBy(() -> chunker(0, 300, 2400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(100, 300, 2400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(95, 0, 2400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(95, 2400, 300)).isInstanceOf(IllegalArgumentException.class);
    }
}
