"""Significance testing for retrieval comparisons.

A retrieval change is judged by comparing two runs over the same queries. Comparing
mean scores alone cannot tell a real improvement from sampling noise: on a 27-query
set the standard error of mean nDCG@10 was 0.031, so a 0.03 difference in the means
was about one standard error — indistinguishable from chance.

Both tests here are *paired*: they work on the per-query differences rather than on the
two means. Pairing removes the variance caused by some queries simply being harder than
others, which is the dominant source of spread in a small test collection, and is what
makes a modest set able to resolve a modest effect.

The randomisation test is the one Smucker, Allan and Carterette (CIKM 2007) recommend
for IR evaluation over the t-test and the Wilcoxon signed-rank test. Both functions are
pure Python: the quality gate runs in CI, where an extra numerical dependency would be
one more thing to keep working.
"""

from __future__ import annotations

import random
import statistics
from dataclasses import dataclass

__all__ = ["Comparison", "bootstrap_ci", "compare", "paired_deltas", "randomisation_test"]


def paired_deltas(
    baseline: dict[str, float], current: dict[str, float]
) -> tuple[list[float], list[str]]:
    """Per-query differences ``current - baseline``, over the queries both runs scored.

    Returns the deltas and the sorted query ids they correspond to. Queries missing from
    either run are dropped rather than treated as zero: a query that was not scored
    carries no evidence, and counting it as "no change" would dilute the test toward
    finding nothing.
    """
    shared = sorted(set(baseline) & set(current))
    return [current[qid] - baseline[qid] for qid in shared], shared


def randomisation_test(deltas: list[float], iterations: int = 100_000, seed: int = 0) -> float:
    """One-sided p-value for the hypothesis that the change made things worse.

    Under the null hypothesis the two systems are equivalent, so the sign attached to
    each query's difference is arbitrary. Flipping signs at random builds the
    distribution of mean differences that chance alone produces; the p-value is the
    share of that distribution at or below what was actually observed.

    A small p-value means a drop this large is unlikely to be noise. ``seed`` is fixed so
    a gate decision is reproducible from the committed run.
    """
    if not deltas:
        return 1.0
    observed = statistics.fmean(deltas)
    if all(d == 0 for d in deltas):
        return 1.0

    rng = random.Random(seed)
    at_least_as_extreme = 0
    for _ in range(iterations):
        flipped = statistics.fmean(d if rng.random() < 0.5 else -d for d in deltas)
        if flipped <= observed:
            at_least_as_extreme += 1

    # Add-one smoothing: with a finite number of samples a p-value of exactly zero
    # overstates the evidence, and this keeps the result strictly positive.
    return (at_least_as_extreme + 1) / (iterations + 1)


def bootstrap_ci(
    deltas: list[float],
    iterations: int = 100_000,
    seed: int = 0,
    confidence: float = 0.95,
) -> tuple[float, float, float]:
    """Mean difference with a percentile bootstrap confidence interval.

    Resamples queries with replacement to estimate how much the mean difference would
    move on a different sample of queries from the same population. The width of the
    interval is the useful part: an interval spanning zero says the test set cannot
    resolve the change, whatever the point estimate suggests.

    Returns ``(mean, low, high)``.
    """
    if not deltas:
        return 0.0, 0.0, 0.0

    rng = random.Random(seed)
    n = len(deltas)
    means = sorted(statistics.fmean(rng.choices(deltas, k=n)) for _ in range(iterations))

    tail = (1.0 - confidence) / 2.0
    low = means[int(tail * iterations)]
    high = means[min(int((1.0 - tail) * iterations), iterations - 1)]
    return statistics.fmean(deltas), low, high


@dataclass(frozen=True)
class Comparison:
    """The result of comparing a run against a baseline over shared queries."""

    queries: int
    mean_delta: float
    ci_low: float
    ci_high: float
    p_value: float
    improved: int
    regressed: int
    unchanged: int

    @property
    def resolvable(self) -> bool:
        """Whether the interval excludes zero, i.e. the set can resolve this change."""
        return self.ci_low > 0.0 or self.ci_high < 0.0

    def summary(self) -> str:
        direction = "+" if self.mean_delta >= 0 else ""
        return (
            f"{direction}{self.mean_delta:.4f} "
            f"(95% CI {self.ci_low:+.4f} to {self.ci_high:+.4f}, p={self.p_value:.3f}, "
            f"n={self.queries}: {self.improved} better, {self.regressed} worse, "
            f"{self.unchanged} unchanged)"
        )


def compare(
    baseline: dict[str, float],
    current: dict[str, float],
    iterations: int = 100_000,
    seed: int = 0,
) -> Comparison:
    """Compares two runs over their shared queries."""
    deltas, _ = paired_deltas(baseline, current)
    mean, low, high = bootstrap_ci(deltas, iterations=iterations, seed=seed)
    return Comparison(
        queries=len(deltas),
        mean_delta=mean,
        ci_low=low,
        ci_high=high,
        p_value=randomisation_test(deltas, iterations=iterations, seed=seed),
        improved=sum(1 for d in deltas if d > 0),
        regressed=sum(1 for d in deltas if d < 0),
        unchanged=sum(1 for d in deltas if d == 0),
    )
