"""Paired significance testing.

The properties asserted here are the ones a quality gate depends on: that a change
too small for the test set to resolve is reported as unresolvable, and that a change
large and consistent enough is reported as significant. Iteration counts are lowered
from the production default to keep the suite fast; each test fixes a seed, so the
results are exact rather than approximate.
"""

import pytest

from ml_service.evals.stats import (
    bootstrap_ci,
    compare,
    paired_deltas,
    randomisation_test,
)

ITERATIONS = 2000


class TestPairedDeltas:
    def test_computes_per_query_differences(self) -> None:
        deltas, ids = paired_deltas({"q1": 0.5, "q2": 1.0}, {"q1": 0.75, "q2": 0.5})

        assert ids == ["q1", "q2"]
        assert deltas == pytest.approx([0.25, -0.5])

    def test_drops_queries_missing_from_either_run(self) -> None:
        # A query that one run did not score carries no evidence. Treating it as an
        # unchanged pair would pull the mean toward zero and hide a real change.
        deltas, ids = paired_deltas({"q1": 0.5, "q2": 1.0}, {"q1": 1.0, "q3": 1.0})

        assert ids == ["q1"]
        assert deltas == pytest.approx([0.5])

    def test_no_shared_queries_yields_nothing(self) -> None:
        assert paired_deltas({"q1": 1.0}, {"q2": 1.0}) == ([], [])


class TestRandomisationTest:
    def test_consistent_improvement_is_not_a_regression(self) -> None:
        # Every query improved, so the one-sided test for a drop should see nothing.
        p = randomisation_test([0.2] * 12, iterations=ITERATIONS)

        assert p > 0.9

    def test_consistent_regression_is_significant(self) -> None:
        p = randomisation_test([-0.2] * 12, iterations=ITERATIONS)

        assert p < 0.01

    def test_noise_around_zero_is_not_significant(self) -> None:
        deltas = [0.3, -0.3, 0.2, -0.2, 0.1, -0.1, 0.25, -0.25]

        assert randomisation_test(deltas, iterations=ITERATIONS) > 0.05

    def test_one_large_drop_among_unchanged_queries_is_not_significant(self) -> None:
        # A single query moving cannot establish a systematic regression, however
        # large its own drop. This is the case a mean-only gate gets wrong.
        deltas = [0.0] * 26 + [-0.8]

        assert randomisation_test(deltas, iterations=ITERATIONS) > 0.05

    def test_identical_runs_return_one(self) -> None:
        assert randomisation_test([0.0] * 10, iterations=ITERATIONS) == 1.0

    def test_empty_input_returns_one(self) -> None:
        assert randomisation_test([], iterations=ITERATIONS) == 1.0

    def test_is_reproducible_for_a_fixed_seed(self) -> None:
        deltas = [0.1, -0.2, 0.3, -0.05, 0.0, 0.15]

        first = randomisation_test(deltas, iterations=ITERATIONS, seed=7)
        second = randomisation_test(deltas, iterations=ITERATIONS, seed=7)

        assert first == second


class TestBootstrapCi:
    def test_interval_brackets_the_mean(self) -> None:
        mean, low, high = bootstrap_ci([0.1, 0.2, 0.15, 0.05], iterations=ITERATIONS)

        assert low <= mean <= high

    def test_constant_deltas_give_a_zero_width_interval(self) -> None:
        mean, low, high = bootstrap_ci([0.25] * 20, iterations=ITERATIONS)

        assert (mean, low, high) == pytest.approx((0.25, 0.25, 0.25))

    def test_noisy_deltas_give_an_interval_spanning_zero(self) -> None:
        _, low, high = bootstrap_ci([0.6, -0.6, 0.5, -0.5, 0.4, -0.4], iterations=ITERATIONS)

        assert low < 0 < high

    def test_empty_input_is_all_zero(self) -> None:
        assert bootstrap_ci([], iterations=ITERATIONS) == (0.0, 0.0, 0.0)


class TestCompare:
    def test_counts_improved_regressed_and_unchanged(self) -> None:
        result = compare(
            {"a": 0.5, "b": 0.5, "c": 0.5},
            {"a": 1.0, "b": 0.0, "c": 0.5},
            iterations=ITERATIONS,
        )

        assert (result.improved, result.regressed, result.unchanged) == (1, 1, 1)
        assert result.queries == 3

    def test_a_change_the_set_cannot_resolve_is_reported_as_such(self) -> None:
        # Mean delta is negative, but the queries disagree wildly. Reporting this as a
        # regression is exactly the false alarm a point-estimate gate produces.
        baseline = {f"q{i}": 0.5 for i in range(8)}
        current = {
            "q0": 1.0,
            "q1": 0.0,
            "q2": 1.0,
            "q3": 0.0,
            "q4": 1.0,
            "q5": 0.0,
            "q6": 0.0,
            "q7": 0.4,
        }

        result = compare(baseline, current, iterations=ITERATIONS)

        assert not result.resolvable
        assert result.ci_low < 0 < result.ci_high

    def test_a_consistent_regression_is_resolvable(self) -> None:
        baseline = {f"q{i}": 0.9 for i in range(20)}
        current = {f"q{i}": 0.7 for i in range(20)}

        result = compare(baseline, current, iterations=ITERATIONS)

        assert result.resolvable
        assert result.ci_high < 0
        assert result.p_value < 0.01

    def test_summary_states_the_interval_and_the_split(self) -> None:
        result = compare({"a": 0.5}, {"a": 0.75}, iterations=ITERATIONS)

        summary = result.summary()

        assert "95% CI" in summary
        assert "n=1" in summary
        assert "1 better" in summary
