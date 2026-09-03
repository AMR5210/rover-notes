"""Pairwise comparison of two recorded eval runs.

The properties asserted here are the ones a sweep depends on: that the comparison is
driven by the per-query block rather than the reported means, that a run file without
one is rejected rather than silently compared on nothing, and that the metric under
test is selectable.
"""

import json
from pathlib import Path
from typing import Any

import pytest

from ml_service.evals.compare import load_run, main, per_query_scores


def write_run(path: Path, per_query: list[dict[str, Any]], ndcg: float = 0.9) -> Path:
    path.write_text(
        json.dumps(
            {
                "mode": "hybrid",
                "metrics": {"ndcg@10": ndcg, "recall@5": 1.0, "recall@20": 1.0, "mrr": 0.9},
                "latency_ms": {"mean": 20.0, "p95": 40.0},
                "per_query": per_query,
            }
        )
    )
    return path


class TestLoadRun:
    def test_rejects_a_run_without_per_query_scores(self, tmp_path: Path) -> None:
        # An aggregate-only file would compare as zero shared queries, which reads as
        # "no difference" rather than as "nothing was measured".
        path = tmp_path / "aggregate.json"
        path.write_text(json.dumps({"metrics": {"ndcg@10": 0.9}}))

        with pytest.raises(ValueError, match="per_query"):
            load_run(path)


class TestPerQueryScores:
    def test_reads_the_requested_metric(self, tmp_path: Path) -> None:
        run = load_run(
            write_run(tmp_path / "run.json", [{"id": "q1", "ndcg@10": 0.5, "recall@5": 1.0}])
        )

        assert per_query_scores(run, "ndcg@10") == {"q1": 0.5}
        assert per_query_scores(run, "recall@5") == {"q1": 1.0}

    def test_skips_queries_missing_the_metric(self, tmp_path: Path) -> None:
        run = load_run(
            write_run(tmp_path / "run.json", [{"id": "q1", "ndcg@10": 0.5}, {"id": "q2"}])
        )

        assert per_query_scores(run, "ndcg@10") == {"q1": 0.5}


class TestMain:
    def test_reports_the_paired_delta_not_the_difference_of_means(
        self, tmp_path: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        # The headline metrics are deliberately equal while every query improved. A
        # comparison driven by the means would report no change.
        baseline = write_run(
            tmp_path / "a.json",
            [{"id": "q1", "ndcg@10": 0.2}, {"id": "q2", "ndcg@10": 0.4}],
            ndcg=0.9,
        )
        current = write_run(
            tmp_path / "b.json",
            [{"id": "q1", "ndcg@10": 0.4}, {"id": "q2", "ndcg@10": 0.6}],
            ndcg=0.9,
        )

        assert main([str(baseline), str(current)]) == 0

        output = capsys.readouterr().out
        assert "+0.2000" in output
        assert "2 better, 0 worse" in output

    def test_says_so_when_the_interval_spans_zero(
        self, tmp_path: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        baseline = write_run(
            tmp_path / "a.json", [{"id": f"q{i}", "ndcg@10": 0.5} for i in range(10)]
        )
        current = write_run(
            tmp_path / "b.json",
            [{"id": f"q{i}", "ndcg@10": 0.5 + (0.1 if i % 2 else -0.1)} for i in range(10)],
        )

        assert main([str(baseline), str(current)]) == 0
        assert "does not separate" in capsys.readouterr().out

    def test_unreadable_run_fails_rather_than_comparing_nothing(
        self, tmp_path: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        run = write_run(tmp_path / "a.json", [{"id": "q1", "ndcg@10": 0.5}])

        assert main([str(run), str(tmp_path / "missing.json")]) == 1
        assert "error:" in capsys.readouterr().err
