package dev.rovernotes.retrieval;

/**
 * How the dense and lexical candidate lists are combined.
 *
 * <p>Selectable so the two can be compared on the eval set rather than argued about.
 * docs/ARCHITECTURE.md chose rank fusion to avoid normalising incomparable score scales; the
 * literature reports that doing the normalisation anyway wins, and this is what settles
 * it for this corpus.
 */
public enum FusionStrategy {

    /** Reciprocal Rank Fusion: sum of {@code 1 / (k + rank)} across the lists. */
    RRF,

    /**
     * Convex combination of min-max normalised scores.
     *
     * <p>{@code alpha * dense + (1 - alpha) * lexical} after scaling each channel's
     * scores into [0, 1] across its own candidate list. Bruch, Gai and Ingber (TOIS
     * 2023) report this beating RRF on every collection they test — nDCG@1000 of 0.454
     * against 0.425 on MS MARCO, 0.542 against 0.514 on NQ — on the argument that rank
     * fusion throws away the distance between scores.
     *
     * <p>They normalise against theoretical bounds rather than the observed minimum and
     * maximum. Cosine similarity has bounds; {@code ts_rank} does not, so the
     * normalisation here is over the candidate list, which makes a document's score
     * depend slightly on what else was retrieved.
     */
    CONVEX
}
