"""Metric correctness, checked against hand-computed values.

Every quality claim the project makes rests on these three numbers. A metric that is
subtly wrong is worse than no metric, because it produces confident nonsense — so the
expected values here are derived by hand rather than captured from a previous run.
"""

import math

from ml_service.evals.metrics import dcg, ndcg_at_k, recall_at_k, reciprocal_rank

# The ideal DCG for two relevant results: 1/log2(2) + 1/log2(3)
IDEAL_TWO = 1 + 1 / math.log2(3)


class TestDcg:
    def test_relevant_first_scores_one(self) -> None:
        assert dcg([1, 0, 0], 3) == 1.0

    def test_discount_follows_log2_of_rank_plus_one(self) -> None:
        # rank 3 -> 1/log2(4) = 0.5
        assert dcg([0, 0, 1], 3) == 0.5

    def test_hand_computed_case(self) -> None:
        # 1/log2(2) + 1/log2(4) = 1 + 0.5
        assert dcg([1, 0, 1, 0, 0], 5) == 1.5

    def test_respects_cutoff(self) -> None:
        assert dcg([0, 0, 1], 2) == 0.0


class TestNdcg:
    def test_perfect_ordering_scores_one(self) -> None:
        assert ndcg_at_k([1, 1, 0, 0, 0], 5) == 1.0

    def test_hand_computed_case(self) -> None:
        assert ndcg_at_k([1, 0, 1, 0, 0], 5) == 1.5 / IDEAL_TWO

    def test_worst_ordering_of_same_results(self) -> None:
        expected = (1 / math.log2(5) + 1 / math.log2(6)) / IDEAL_TWO
        assert ndcg_at_k([0, 0, 0, 1, 1], 5) == expected

    def test_ordering_changes_the_score(self) -> None:
        # The point of a ranking metric: same results, different order, different score.
        assert ndcg_at_k([1, 1, 0, 0], 4) > ndcg_at_k([0, 0, 1, 1], 4)

    def test_nothing_relevant_scores_zero(self) -> None:
        assert ndcg_at_k([0, 0, 0], 3) == 0.0

    def test_empty_result_list_scores_zero(self) -> None:
        assert ndcg_at_k([], 10) == 0.0


class TestRecall:
    def test_counts_against_the_golden_total_not_the_result_list(self) -> None:
        # Two of three known-relevant documents were retrieved.
        assert recall_at_k([1, 0, 1, 0, 0], 5, 3) == 2 / 3

    def test_respects_cutoff(self) -> None:
        assert recall_at_k([1, 0, 1, 0, 0], 2, 3) == 1 / 3

    def test_zero_when_nothing_is_known_relevant(self) -> None:
        assert recall_at_k([0], 5, 0) == 0.0

    def test_clamped_at_one(self) -> None:
        assert recall_at_k([1, 1, 1], 3, 2) == 1.0


class TestReciprocalRank:
    def test_first_position(self) -> None:
        assert reciprocal_rank([1, 0, 0]) == 1.0

    def test_second_position(self) -> None:
        assert reciprocal_rank([0, 1, 0]) == 0.5

    def test_third_position(self) -> None:
        assert reciprocal_rank([0, 0, 1]) == 1 / 3

    def test_only_the_first_hit_counts(self) -> None:
        assert reciprocal_rank([0, 1, 1, 1]) == 0.5

    def test_no_relevant_result(self) -> None:
        assert reciprocal_rank([0, 0, 0]) == 0.0
