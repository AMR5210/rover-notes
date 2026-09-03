package dev.rovernotes.ingestion;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.rovernotes.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Splits a document where its subject changes rather than at a character count.
 *
 * <p>Every sentence is embedded, and the cosine distance between each consecutive pair
 * forms a curve across the document. A boundary is taken wherever that distance exceeds
 * a percentile of the distances <em>within that document</em>, which makes the threshold
 * relative to how varied the document already is: a uniform reference page and a rambling
 * set of notes get different cut-offs from the same setting.
 *
 * <p>There is no overlap. Fixed windows need it because a boundary drawn at an arbitrary
 * character can land mid-fact, and a fact split across a boundary is retrievable from
 * neither side. A boundary drawn at a change of subject is by construction a place where
 * a fact is unlikely to straddle, so the overlap earns nothing and costs duplicate text
 * in the index.
 *
 * <p>The cost is one embedding per sentence at ingest, on top of one per chunk. Measured
 * over the 32-document eval corpus that is 309 embedded texts against 32 — 9.7 times the
 * work, and 4.9 seconds of wall clock against 1.9. It is paid once per write and stays
 * off the read path.
 *
 * <p>That per-sentence cost is paid again on every re-index, which is what makes it
 * decisive rather than incidental. Chunk reuse keeps a vector whose text is unchanged, and
 * boundary detection runs before any chunk can be found reusable — so re-indexing an
 * unedited document costs one embedding per sentence and saves nothing. Measured over the
 * four longest corpus documents: 83 embedded texts for a re-index that changes nothing,
 * against zero for fixed windows. Where the split itself is concerned this strategy is the
 * better one — an insertion near the top re-embeds 4 chunks against the fixed window's 12 —
 * but the toll is about seven times the document's whole chunk cost, so it does not come
 * back. Caching sentence embeddings is what would change that.
 *
 * <p>It does not pay for itself on that corpus, where documents average 1,033 characters
 * and no document reaches the fixed window at all. Splitting one already-coherent
 * document in two costs 0.0505 nDCG@10 at the document level (95% CI -0.0816 to -0.0218)
 * because each half embeds a narrower topic than the query is asking about. The strategy
 * is built for documents long enough that a single embedding spans several subjects; see
 * {@code `docs/RESULTS.md`} for the run and the condition that would change the answer.
 *
 * <p>Pieces are clamped to a minimum and maximum length. Without a minimum, a document
 * whose sentences all differ produces single-sentence chunks with too little context to
 * embed meaningfully; without a maximum, a uniform document collapses into one chunk and
 * the split has bought nothing.
 */
@Component
@ConditionalOnProperty(name = "rover.ingestion.chunking", havingValue = "semantic")
public class SemanticChunker implements ChunkingStrategy {

    /** TEI rejects a request carrying more texts than its max_client_batch_size. */
    private static final int EMBED_BATCH = 32;

    private final EmbeddingClient embeddings;
    private final double percentile;
    private final int minChars;
    private final int maxChars;

    SemanticChunker(EmbeddingClient embeddings,
                    @Value("${rover.ingestion.breakpoint-percentile}") double percentile,
                    @Value("${rover.ingestion.min-chunk-chars}") int minChars,
                    @Value("${rover.ingestion.max-chunk-chars}") int maxChars) {
        if (percentile <= 0 || percentile >= 100) {
            throw new IllegalArgumentException("percentile must be in (0, 100)");
        }
        if (minChars <= 0 || maxChars <= minChars) {
            throw new IllegalArgumentException("need 0 < minChars < maxChars");
        }
        this.embeddings = embeddings;
        this.percentile = percentile;
        this.minChars = minChars;
        this.maxChars = maxChars;
    }

    @Override
    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // A document that already fits in one chunk has nothing to gain from being cut.
        // The percentile always finds a largest distance, so without this guard even a
        // three-sentence note is split at whichever pair happens to differ most, and each
        // half embeds less context than the whole did. Fixed windows get this behaviour
        // for free — a document under the window size is one window — and measuring the
        // two strategies against each other requires them to agree here.
        if (text.strip().length() <= maxChars) {
            return emit(text, List.of(new Span(0, text.length())));
        }

        List<Span> sentences = sentences(text);
        if (sentences.size() < 2) {
            return emit(text, List.of(new Span(0, text.length())));
        }

        double[] distances = consecutiveDistances(sentences, text);
        double threshold = percentile(distances, percentile);

        List<Span> pieces = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] > threshold) {
                pieces.add(new Span(sentences.get(start).start(), sentences.get(i).end()));
                start = i + 1;
            }
        }
        pieces.add(new Span(sentences.get(start).start(),
                sentences.get(sentences.size() - 1).end()));

        return emit(text, clamp(pieces, sentences, distances, text));
    }

    /**
     * Sentence spans, using the JDK's locale-aware boundary rules.
     *
     * <p>{@link BreakIterator} knows that a full stop inside an abbreviation is not a
     * sentence end, which a regular expression on {@code [.!?]} does not. Blank spans —
     * the run of newlines between paragraphs — are dropped rather than embedded.
     */
    private static List<Span> sentences(String text) {
        BreakIterator boundaries = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        boundaries.setText(text);

        List<Span> spans = new ArrayList<>();
        int start = boundaries.first();
        for (int end = boundaries.next(); end != BreakIterator.DONE;
                start = end, end = boundaries.next()) {
            if (!text.substring(start, end).isBlank()) {
                spans.add(new Span(start, end));
            }
        }
        return spans;
    }

    private double[] consecutiveDistances(List<Span> sentences, String text) {
        List<String> texts = sentences.stream().map(s -> text.substring(s.start(), s.end())).toList();

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            vectors.addAll(embeddings.embed(texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()))));
        }

        double[] distances = new double[vectors.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = 1.0 - cosine(vectors.get(i), vectors.get(i + 1));
        }
        return distances;
    }

    /**
     * Linear-interpolated percentile, the definition NumPy and the retrieval literature
     * use. Stated explicitly because the alternative — nearest rank — puts the threshold
     * on an observed value, and a strict {@code >} comparison against it would then never
     * cut at the most distant pair.
     */
    static double percentile(double[] values, double p) {
        if (values.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        double position = (p / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    /**
     * Applies the length bounds to the pieces the breakpoints produced.
     *
     * <p>A piece below the minimum is merged into its neighbour; a piece above the
     * maximum is cut again at its most distant sentence pair, which keeps even the
     * forced splits on a semantic boundary rather than a character offset. A piece with
     * no interior boundary left — one very long sentence — is emitted over-length rather
     * than cut mid-sentence, since the chunk is still the smallest meaningful unit there.
     */
    private List<Span> clamp(List<Span> pieces, List<Span> sentences, double[] distances,
                             String text) {
        List<Span> merged = new ArrayList<>();
        for (Span piece : pieces) {
            if (!merged.isEmpty() && length(piece, text) < minChars) {
                Span previous = merged.remove(merged.size() - 1);
                merged.add(new Span(previous.start(), piece.end()));
            } else {
                merged.add(piece);
            }
        }
        // The first piece can still be short if it was never merged forward into.
        if (merged.size() > 1 && length(merged.get(0), text) < minChars) {
            Span first = merged.remove(0);
            Span second = merged.remove(0);
            merged.add(0, new Span(first.start(), second.end()));
        }

        List<Span> bounded = new ArrayList<>();
        for (Span piece : merged) {
            split(piece, sentences, distances, text, bounded);
        }
        return bounded;
    }

    private void split(Span piece, List<Span> sentences, double[] distances, String text,
                       List<Span> out) {
        if (length(piece, text) <= maxChars) {
            out.add(piece);
            return;
        }

        // Ranked by distance first, then by how evenly the cut divides the piece. The
        // second term matters more than it looks: on uniform text every distance ties, and
        // taking the first eligible boundary then cuts one sentence off the front and
        // recurses on the rest, turning a piece that needed one cut into several. Choosing
        // the most central boundary among equals splits it in half instead.
        int middle = (piece.start() + piece.end()) / 2;
        int cut = -1;
        double best = Double.NEGATIVE_INFINITY;
        int bestOffset = Integer.MAX_VALUE;

        for (int i = 0; i < distances.length; i++) {
            int boundary = sentences.get(i).end();
            if (boundary <= piece.start() || boundary >= piece.end()) {
                continue;
            }
            // Only cut where both halves clear the minimum, or the split just creates
            // another undersized piece for the merge pass to undo.
            if (boundary - piece.start() < minChars || piece.end() - boundary < minChars) {
                continue;
            }
            int offset = Math.abs(boundary - middle);
            if (distances[i] > best || (distances[i] == best && offset < bestOffset)) {
                best = distances[i];
                bestOffset = offset;
                cut = boundary;
            }
        }

        if (cut < 0) {
            out.add(piece);
            return;
        }
        split(new Span(piece.start(), cut), sentences, distances, text, out);
        split(new Span(cut, piece.end()), sentences, distances, text, out);
    }

    private static int length(Span span, String text) {
        return text.substring(span.start(), span.end()).strip().length();
    }

    private static List<TextChunk> emit(String text, List<Span> spans) {
        List<TextChunk> chunks = new ArrayList<>(spans.size());
        int ordinal = 0;
        for (Span span : spans) {
            String body = text.substring(span.start(), span.end()).strip();
            if (!body.isEmpty()) {
                chunks.add(new TextChunk(ordinal++, body, span.start(), span.end()));
            }
        }
        return chunks;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        // A zero vector has no direction, so no angle to it is meaningful. Reporting
        // maximum similarity keeps it from being read as a topic change.
        if (normA == 0 || normB == 0) {
            return 1.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Span(int start, int end) {}
}
