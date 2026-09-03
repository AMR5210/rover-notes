"""Retrieval metrics.

Pure functions over a relevance vector: the ranked list of results reduced to 1 for
relevant and 0 for not, in rank order. Keeping them free of I/O makes them directly
testable against hand-computed values, which matters because every quality claim the
project makes rests on these three numbers being right.
"""

from __future__ import annotations

import math

__all__ = ["dcg", "ndcg_at_k", "recall_at_k", "reciprocal_rank"]


def dcg(relevance: list[int], k: int) -> float:
    """Discounted cumulative gain over the first ``k`` results.

    Each hit contributes ``1 / log2(rank + 1)`` with ranks starting at 1, so a relevant
    result at rank 1 is worth 1.0 and one at rank 3 is worth 0.5. The discount is what
    makes this a ranking metric rather than a set metric.
    """
    return sum(rel / math.log2(rank + 1) for rank, rel in enumerate(relevance[:k], start=1))


def ndcg_at_k(relevance: list[int], k: int) -> float:
    """nDCG@k — the headline retrieval number.

    Normalises DCG against the best achievable ordering of the same results, so a score
    of 1.0 means the relevant results were ranked as well as they possibly could be
    given what was retrieved.

    Returns 0.0 when nothing relevant was retrieved, since there is no ideal ordering to
    normalise against.
    """
    ideal = dcg(sorted(relevance, reverse=True), k)
    if ideal == 0:
        return 0.0
    return dcg(relevance, k) / ideal


def recall_at_k(relevance: list[int], k: int, total_relevant: int) -> float:
    """Fraction of all known-relevant results that appear in the top ``k``.

    Unlike nDCG this needs ``total_relevant`` from the golden set rather than from the
    result list — otherwise a query that retrieved nothing useful would score 1.0 for
    perfectly ranking its zero hits.
    """
    if total_relevant <= 0:
        return 0.0
    return min(sum(relevance[:k]) / total_relevant, 1.0)


def reciprocal_rank(relevance: list[int]) -> float:
    """1 / rank of the first relevant result, or 0.0 if there is none.

    Averaged across queries this is MRR. It answers a different question from nDCG:
    not "how good is the ordering" but "how far does the reader have to scroll".
    """
    for rank, rel in enumerate(relevance, start=1):
        if rel:
            return 1.0 / rank
    return 0.0
