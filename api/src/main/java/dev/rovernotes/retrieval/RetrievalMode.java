package dev.rovernotes.retrieval;

/**
 * Which retrieval channels contribute to a result set.
 *
 * <p>Selectable at runtime so the eval harness can attribute a change to the channel
 * that produced it. Scoring one channel at a time is what turns "hybrid retrieval helps"
 * into a number for each half, and it is how the rows in {@code `docs/RESULTS.md`} are
 * measured.
 */
public enum RetrievalMode {

    /** Query embedding compared against chunk embeddings with pgvector's cosine operator. */
    DENSE,

    /** PostgreSQL full-text search over the generated {@code tsv} column. */
    LEXICAL,

    /** Both channels, merged with Reciprocal Rank Fusion. */
    HYBRID
}
