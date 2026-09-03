"""Retrieval evaluation runner.

Measures the live pipeline over HTTP rather than reimplementing its SQL, so what is
scored is what actually ships. Every stage added in Weeks 4 and 5 is judged by the
delta it produces here.

    uv run python -m ml_service.evals.run --seed        # ingest the corpus first
    uv run python -m ml_service.evals.run               # score and print
    uv run python -m ml_service.evals.run --gate        # fail on a significant regression
    uv run python -m ml_service.evals.run --write-baseline

``--golden`` selects the suite. ``evals/golden`` holds natural-language questions;
``evals/golden-known-item`` holds lookups by identifier or title, which is a different
retrieval task with its own baseline:

    uv run python -m ml_service.evals.run \\
        --golden evals/golden-known-item --baseline evals/baseline-known-item.json
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

from ml_service.evals.dataset import (
    GoldenQuery,
    corpus_documents,
    judge,
    load_golden_set,
)
from ml_service.evals.metrics import ndcg_at_k, recall_at_k, reciprocal_rank
from ml_service.evals.stats import compare

REPO_ROOT = Path(__file__).resolve().parents[4]
EVALS_DIR = REPO_ROOT / "evals"
GOLDEN_DIR = EVALS_DIR / "golden"
CORPUS_DIR = EVALS_DIR / "corpus"
RUNS_DIR = EVALS_DIR / "runs"
BASELINE = EVALS_DIR / "baseline.json"

# Retrieve deeper than the reporting cutoff so recall@20 is measurable.
RETRIEVE_LIMIT = 20


def seed_corpus(client: httpx.Client, directory: Path = CORPUS_DIR) -> int:
    """Ingests ``*.md`` from a corpus directory, using each filename stem as the title.

    The title is what the golden set's ``doc`` field matches against, which is why the
    slug has to survive ingestion unchanged.
    """
    count = 0
    for slug, text in corpus_documents(directory).items():
        response = client.post(
            "/api/notes",
            json={"title": slug, "content": text},
            timeout=30.0,
        )
        response.raise_for_status()
        count += 1
    return count


def await_indexed(client: httpx.Client, expected: int, timeout: float | None = None) -> None:
    """Blocks until every seeded document is indexed, or fails loudly.

    A write returns before its text is searchable, so scoring immediately after seeding
    measures whatever happened to be indexed by then. This previously waited a fixed three
    seconds, which is a guess about embedding throughput on unknown hardware; on a slower
    machine it scores a half-built index and reports the result as a retrieval number.

    Two conditions are checked, because they fail differently. The document count catches
    a corpus that was seeded twice — two runs against one database produce a plausible
    number from a corpus nobody intended, which is not visible in any metric afterwards.
    The indexing backlog catches the race.

    The default deadline scales with the corpus, since a fixed one is either generous for
    42 documents or short for several thousand. It bounds a stall rather than setting a
    throughput target.
    """
    if timeout is None:
        timeout = max(120.0, expected * 0.2)

    total = client.get("/api/notes", params={"limit": 1}).json()["total"]
    if total != expected:
        raise RuntimeError(
            f"{total} documents present after seeding {expected} — the corpus is not what "
            "was intended. Truncate it and seed once."
        )

    deadline = time.monotonic() + timeout
    while True:
        response = client.get("/actuator/metrics/rover.ingestion.backlog")
        response.raise_for_status()
        backlog = response.json()["measurements"][0]["value"]
        if backlog == 0:
            return
        if time.monotonic() > deadline:
            raise RuntimeError(
                f"{backlog:.0f} documents still unindexed after {timeout:.0f}s — scoring "
                "now would measure an incomplete index."
            )
        time.sleep(0.5)


def search(
    client: httpx.Client,
    query: str,
    mode: str | None = None,
    rerank: bool | None = None,
    route: bool | None = None,
) -> tuple[list[dict[str, str]], float]:
    params: dict[str, str | int | bool] = {"q": query, "limit": RETRIEVE_LIMIT}
    if mode:
        params["mode"] = mode
    if rerank is not None:
        params["rerank"] = rerank
    if route is not None:
        params["route"] = route
    started = time.perf_counter()
    response = client.get("/api/search", params=params, timeout=30.0)
    response.raise_for_status()
    elapsed_ms = (time.perf_counter() - started) * 1000
    return response.json().get("results", []), elapsed_ms


def evaluate(
    client: httpx.Client,
    queries: list[GoldenQuery],
    mode: str | None = None,
    rerank: bool | None = None,
    route: bool | None = None,
    level: str = "passage",
) -> dict[str, Any]:
    scored = [q for q in queries if not q.unanswerable]
    abstention = [q for q in queries if q.unanswerable]

    ndcg10: list[float] = []
    recall5: list[float] = []
    recall20: list[float] = []
    rr: list[float] = []
    latencies: list[float] = []
    per_query: list[dict[str, Any]] = []

    for query in scored:
        results, elapsed_ms = search(client, query.query, mode, rerank, route)
        relevance = judge(query, results, level)
        latencies.append(elapsed_ms)

        q_ndcg = ndcg_at_k(relevance, 10)
        q_recall5 = recall_at_k(relevance, 5, query.total_relevant)
        q_recall20 = recall_at_k(relevance, 20, query.total_relevant)
        q_rr = reciprocal_rank(relevance)

        ndcg10.append(q_ndcg)
        recall5.append(q_recall5)
        recall20.append(q_recall20)
        rr.append(q_rr)
        per_query.append(
            {
                "id": query.id,
                "query": query.query,
                "ndcg@10": round(q_ndcg, 4),
                "recall@5": round(q_recall5, 4),
                "recall@20": round(q_recall20, 4),
                "rr": round(q_rr, 4),
                "hits": sum(relevance),
            }
        )

    # Unanswerable queries are still searched, to record how much the retriever surfaces
    # for a question the corpus cannot answer. Generation is what must decline; this
    # number only says how much material it will be handed.
    noise = [len(search(client, q.query, mode, rerank, route)[0]) for q in abstention]

    def mean(values: list[float]) -> float:
        return round(statistics.fmean(values), 4) if values else 0.0

    return {
        "timestamp": datetime.now(UTC).isoformat(),
        "mode": mode or "default",
        "rerank": "default" if rerank is None else rerank,
        "route": "default" if route is None else route,
        "relevance": level,
        "queries_scored": len(scored),
        "queries_unanswerable": len(abstention),
        "metrics": {
            "ndcg@10": mean(ndcg10),
            "recall@5": mean(recall5),
            "recall@20": mean(recall20),
            "mrr": mean(rr),
        },
        "latency_ms": {
            "mean": round(statistics.fmean(latencies), 1) if latencies else 0.0,
            "p95": round(sorted(latencies)[int(len(latencies) * 0.95) - 1], 1)
            if latencies
            else 0.0,
        },
        "unanswerable_mean_hits": round(statistics.fmean(noise), 2) if noise else 0.0,
        "per_query": per_query,
    }


def print_report(report: dict[str, Any]) -> None:
    m = report["metrics"]
    print()
    print(
        f"  Retrieval evaluation [{report['mode']}, rerank={report['rerank']}, "
        f"route={report['route']}] — "
        f"{report['queries_scored']} scored queries "
        f"({report['queries_unanswerable']} unanswerable, excluded from ranking)"
    )
    print("  " + "-" * 52)
    for name in ("ndcg@10", "recall@5", "recall@20", "mrr"):
        print(f"  {name:<24s} {m[name]:.4f}")
    print(f"  {'retrieval p95 (ms)':<24s} {report['latency_ms']['p95']:.1f}")
    print("  " + "-" * 52)

    weakest = sorted(report["per_query"], key=lambda q: q["ndcg@10"])[:5]
    if weakest and weakest[0]["ndcg@10"] < 1.0:
        print("\n  Weakest queries (where the next improvement should aim):")
        for q in weakest:
            if q["ndcg@10"] < 1.0:
                print(f"    {q['ndcg@10']:.3f}  {q['id']}  {q['query'][:58]}")
    print()


def gate(report: dict[str, Any], threshold: float, baseline_path: Path, alpha: float = 0.05) -> int:
    """Fails the build on a regression that is both material and statistically real.

    Comparing the two means alone cannot separate a regression from sampling noise. The
    comparison here is paired over the queries both runs scored, and reports the
    confidence interval alongside the point estimate so the resolution of the test set
    is visible in the build log rather than assumed.

    The build fails when the drop clears ``threshold`` *and* a randomisation test puts it
    below ``alpha``. Requiring both means a small test collection cannot fail the build on
    one unlucky query. The cost is the opposite error: a real regression too small for the
    set to resolve passes, which is an argument for growing the golden set rather than for
    tightening the threshold.
    """
    if not baseline_path.exists():
        print(f"No baseline at {baseline_path} — run with --write-baseline first.", file=sys.stderr)
        return 1

    baseline = json.loads(baseline_path.read_text())
    before = baseline["metrics"]["ndcg@10"]
    after = report["metrics"]["ndcg@10"]

    baseline_scores = {q["id"]: q["ndcg@10"] for q in baseline.get("per_query", [])}
    current_scores = {q["id"]: q["ndcg@10"] for q in report.get("per_query", [])}
    result = compare(baseline_scores, current_scores)

    print(f"  nDCG@10  baseline {before:.4f} -> current {after:.4f}")
    print(f"  paired   {result.summary()}")

    if not result.resolvable and result.mean_delta != 0.0:
        print("  note: the interval spans zero — this set cannot resolve a change this size.")

    material = result.mean_delta < -threshold
    significant = result.p_value < alpha

    if material and significant:
        print(
            f"  FAIL: {-result.mean_delta:.4f} below baseline, p={result.p_value:.3f} "
            f"— a real regression past the {threshold:.0%} threshold.",
            file=sys.stderr,
        )
        return 1
    if material:
        print(
            f"  PASS with a warning: down {-result.mean_delta:.4f}, past the "
            f"{threshold:.0%} threshold, but p={result.p_value:.3f} does not establish it."
        )
        return 0
    print("  PASS")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Retrieval evaluation over the live API")
    parser.add_argument("--api", default="http://localhost:8080", help="base URL of the API")
    parser.add_argument("--seed", action="store_true", help="ingest evals/corpus first")
    # The known-item slice is a different retrieval task from the semantic one, so it is
    # scored as its own suite against its own baseline. Averaging the two into a single
    # number would let a gain on one hide a regression in the other.
    parser.add_argument(
        "--golden",
        type=Path,
        default=GOLDEN_DIR,
        help=f"directory of golden *.jsonl files (default: {GOLDEN_DIR.name})",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=BASELINE,
        help=f"baseline file to gate against and write (default: {BASELINE.name})",
    )
    parser.add_argument(
        "--corpus",
        type=Path,
        default=CORPUS_DIR,
        help=f"directory of corpus *.md files to seed (default: {CORPUS_DIR.name})",
    )
    parser.add_argument(
        "--mode",
        choices=("dense", "lexical", "hybrid"),
        help="score one retrieval channel instead of the API's configured default",
    )
    rerank = parser.add_mutually_exclusive_group()
    rerank.add_argument(
        "--rerank",
        dest="rerank",
        action="store_true",
        default=None,
        help="force the cross-encoder on, whatever the API is configured to do",
    )
    rerank.add_argument(
        "--no-rerank",
        dest="rerank",
        action="store_false",
        default=None,
        help="force the cross-encoder off",
    )
    route = parser.add_mutually_exclusive_group()
    route.add_argument(
        "--route",
        dest="route",
        action="store_true",
        default=None,
        help="force the query router on, whatever the API is configured to do",
    )
    route.add_argument(
        "--no-route", dest="route", action="store_false", default=None, help="force it off"
    )
    parser.add_argument(
        "--relevance",
        choices=("passage", "document"),
        default="passage",
        help="whether a hit must contain the labelled phrase (default) or only come "
        "from the right document",
    )
    parser.add_argument(
        "--gate", action="store_true", help="compare against the committed baseline"
    )
    parser.add_argument("--threshold", type=float, default=0.03, help="allowed nDCG@10 regression")
    parser.add_argument(
        "--write-baseline", action="store_true", help="record this run as the baseline"
    )
    args = parser.parse_args(argv)

    queries = load_golden_set(args.golden)
    if not queries:
        print(f"No golden queries found in {args.golden}", file=sys.stderr)
        return 1

    with httpx.Client(base_url=args.api) as client:
        if args.seed:
            expected = seed_corpus(client, args.corpus)
            print(f"  seeded {expected} corpus documents from {args.corpus.name}")
            await_indexed(client, expected)
        report = evaluate(client, queries, args.mode, args.rerank, args.route, args.relevance)

    # Recorded so a run file states which suite produced it; two suites score different
    # tasks and their numbers are not comparable.
    report["golden"] = args.golden.name

    print_report(report)

    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    suffix = (
        f"{args.golden.name}-{report['mode']}-rerank{report['rerank']}"
        f"-route{report['route']}-{report['relevance']}"
    )
    run_path = RUNS_DIR / f"{datetime.now(UTC):%Y%m%dT%H%M%SZ}-{suffix}.json"
    run_path.write_text(json.dumps(report, indent=2) + "\n")

    if args.write_baseline:
        args.baseline.write_text(json.dumps(report, indent=2) + "\n")
        print(f"  baseline written to {args.baseline}")

    if args.gate:
        return gate(report, args.threshold, args.baseline)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
