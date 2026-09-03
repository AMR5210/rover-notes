package dev.rovernotes.ingestion;

import java.util.List;

/**
 * Splits document text into the units that get embedded and retrieved.
 *
 * <p>Two implementations exist so they can be compared against the eval harness rather
 * than argued about: {@link Chunker} cuts fixed-size overlapping windows, and
 * {@link SemanticChunker} cuts where the text changes subject. Which one runs is
 * {@code rover.ingestion.chunking}; the delta each produces is in {@code `docs/RESULTS.md`}.
 */
public interface ChunkingStrategy {

    /**
     * @return chunks in document order, each carrying the character offsets of its span
     *         so a retrieved chunk can be traced back to its exact location for
     *         citation. Empty for blank input.
     */
    List<TextChunk> chunk(String text);

    /** A chunk of text plus the span it occupies in the source document. */
    record TextChunk(int ordinal, String content, int charStart, int charEnd) {}
}
