package dev.rovernotes.retrieval;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Stage 0: chooses a retrieval channel for queries where fusion is measurably the wrong
 * strategy.
 *
 * <p>The two channels fail in different places. Measured over the 89-query known-item
 * slice, the dense channel averages 0.6965 nDCG@10 on identifier lookups against 0.9710
 * for the lexical channel: an embedding compresses a rare token into general topical
 * meaning, which is what an embedding is for. Fusion sits between the two at 0.8847, so
 * running it on a query the lexical channel already answers costs 0.0863 (95% CI +0.0167
 * to +0.1603, 7 queries better and 1 worse of 30).
 *
 * <p>That is the only rule this router applies. On the same slice, title lookups favour
 * the dense channel by 0.0185 with an interval whose lower bound is zero, and misspelled
 * titles are already served best by fusion. Neither is supported well enough to act on,
 * so both keep the configured default — a router of one rule rather than a classifier.
 *
 * <p>See {@code `docs/RESULTS.md`} for the runs behind these numbers.
 */
@Component
public class QueryRouter {

    /**
     * Whether a token carries a mark of being an identifier rather than a word.
     *
     * <p>Deliberately the same test the eval slice is generated with
     * ({@code ml_service.evals.build_known_item}), which is a limit on what the slice can
     * say: a router using the generating rule classifies those queries perfectly, so the
     * measured gain is conditional on correct classification. The complementary number —
     * how often this fires on a query it should have left alone — is measured against
     * {@code evals/golden}, where it should never fire.
     *
     * <p>An initial capital is not a mark. Otherwise the first word of any sentence
     * qualifies, and a one-word question routes as an identifier.
     */
    static boolean looksLikeIdentifier(String token) {
        if (token.length() < 4) {
            return false;
        }
        String inner = token.substring(1, token.length() - 1);
        return token.indexOf('_') >= 0
                || token.chars().anyMatch(Character::isDigit)
                || inner.indexOf('.') >= 0
                || inner.indexOf('-') >= 0
                || inner.chars().anyMatch(Character::isUpperCase);
    }

    /**
     * The channel this query should use, or empty to leave the decision alone.
     *
     * <p>A routed query must be a single token. Multiple tokens mean the reader is
     * describing what they want rather than naming it, and the measurement covering this
     * rule was made over single-token lookups only.
     */
    public Optional<RetrievalMode> route(String query) {
        if (query == null) {
            return Optional.empty();
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return looksLikeIdentifier(trimmed) ? Optional.of(RetrievalMode.LEXICAL) : Optional.empty();
    }
}
