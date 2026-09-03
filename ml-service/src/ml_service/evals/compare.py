"""Paired comparison between two recorded eval runs.

``run.py`` scores one configuration and writes it to ``evals/runs/``. Deciding whether
one configuration is better than another means comparing two of those files over the
queries they share, which is what this does — the same paired randomisation test and
bootstrap interval the CI gate uses, applied to any two runs rather than to a run and
the committed baseline.

    uv run python -m ml_service.evals.compare evals/runs/A.json evals/runs/B.json

The point estimate is reported alongside the interval so a change the set cannot
resolve is visible as such. A configuration is only adopted when the interval excludes
zero; see ``docs/RESULTS.md`` for the runs behind each value that has moved.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from ml_service.evals.stats import compare

RETRIEVAL_METRICS = ("ndcg@10", "recall@5", "recall@20", "mrr")
GENERATION_METRICS = ("faithfulness", "citation_precision", "claim_citation_rate")


# A run is identified by what its per-query entries carry rather than by a flag, so the
# same command compares two retrieval runs or two generation runs without being told
# which it was handed.
def metrics_for(run: dict[str, Any]) -> tuple[str, ...]:
    first = run["per_query"][0] if run.get("per_query") else {}
    return GENERATION_METRICS if "faithfulness" in first else RETRIEVAL_METRICS


def load_run(path: Path) -> dict[str, Any]:
    data: dict[str, Any] = json.loads(path.read_text())
    if "per_query" not in data:
        raise ValueError(f"{path} has no per_query block — it cannot be compared pairwise")
    return data


def per_query_scores(run: dict[str, Any], metric: str) -> dict[str, float]:
    return {q["id"]: float(q[metric]) for q in run["per_query"] if metric in q}


def describe(label: str, path: Path, run: dict[str, Any]) -> str:
    metrics = run["metrics"]
    names = metrics_for(run)
    scores = "  ".join(f"{name} {metrics[name]:.4f}" for name in names if name in metrics)
    latency = run.get("latency_ms", {}).get("p95")
    tail = f"  p95 {latency:.0f} ms" if latency is not None else ""
    return f"  {label:<10s} {path.name}\n             {scores}{tail}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compare two eval runs over shared queries")
    parser.add_argument("baseline", type=Path, help="the run being compared against")
    parser.add_argument("current", type=Path, help="the run under test")
    parser.add_argument(
        "--metric",
        choices=RETRIEVAL_METRICS + GENERATION_METRICS,
        help="per-query metric to test (default: the first one the run reports)",
    )
    args = parser.parse_args(argv)

    try:
        baseline = load_run(args.baseline)
        current = load_run(args.current)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    metric = args.metric or metrics_for(baseline)[0]
    result = compare(
        per_query_scores(baseline, metric),
        per_query_scores(current, metric),
    )

    print()
    print(describe("baseline", args.baseline, baseline))
    print(describe("current", args.current, current))
    print()
    print(f"  paired {metric}: {result.summary()}")
    if not result.resolvable:
        print("  The interval spans zero — this set does not separate these two runs.")
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
