"""Tests for the parts of the generation eval that need no model.

Citation parsing, span validation and aggregation decide whether an answer is counted as
grounded, so a fault in them would move every judged number too. They are tested against
hand-written cases rather than against a live model, which is also what makes them
runnable in CI where the judged half is not.
"""

from __future__ import annotations

import json

import httpx
import pytest

from ml_service.evals.dataset import GoldenQuery
from ml_service.evals.generation import (
    cited_numbers,
    evaluate,
    grounded_fraction,
    render_sources,
    resolve_sources,
    score_answerable,
    score_unanswerable,
    sentences,
)
from ml_service.evals.judge import parse_json


class TestCitationParsing:
    def test_reads_single_and_grouped_references(self):
        assert cited_numbers("RRF fuses ranked lists [1]. Scores are not summed [2, 3].") == [
            1,
            2,
            3,
        ]

    def test_reads_adjacent_brackets(self):
        assert cited_numbers("Both channels are searched [1][4].") == [1, 4]

    def test_reports_first_appearance_order_without_duplicates(self):
        assert cited_numbers("[3] then [1] then [3] again.") == [3, 1]

    def test_ignores_markdown_links(self):
        # A link's label is not a citation, and counting it as one would credit an
        # ungrounded answer with a reference it never made.
        assert cited_numbers("See [the runbook](https://example.test/1) for detail.") == []

    def test_ignores_brackets_holding_anything_but_numbers(self):
        assert cited_numbers("The value is [unknown] and the flag is [x, 2].") == []

    def test_no_citations_is_empty(self):
        assert cited_numbers("Nothing in your notes covers this yet.") == []


class TestGrounding:
    def test_every_sentence_cited(self):
        assert grounded_fraction("One thing [1]. Another thing [2].") == 1.0

    def test_no_sentence_cited(self):
        assert grounded_fraction("One thing. Another thing.") == 0.0

    def test_partial(self):
        assert grounded_fraction("Cited [1]. Uncited. Cited again [2].") == pytest.approx(2 / 3)

    def test_empty_answer_is_not_grounded(self):
        assert grounded_fraction("   ") == 0.0

    def test_sentences_split_on_terminators(self):
        assert sentences("A first one. A second one! A third?") == [
            "A first one.",
            "A second one!",
            "A third?",
        ]


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler), base_url="http://api.test")


DOCUMENT = "RRF fuses ranked lists rather than scores. Reranking is off by default."


def _notes_handler(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"id": "d1", "title": "hybrid", "content": DOCUMENT})


def _citation(number: int = 1, start: int = 0, end: int = 42) -> dict:
    return {
        "number": number,
        "documentId": "d1",
        "title": "hybrid",
        "charStart": start,
        "charEnd": end,
    }


def _claim(cited: list[int], by_cited: bool, by_any: bool) -> dict:
    return {
        "text": "a claim",
        "cited": cited,
        "supported_by_cited": by_cited,
        "supported_by_any": by_any,
    }


def _stub_judge(payload: dict):
    """A judge that returns a fixed verdict, so scoring is tested without a model."""

    def judge(prompt: str) -> str:
        return json.dumps(payload)

    judge.name = "stub"  # type: ignore[attr-defined]
    return judge


class TestSpanResolution:
    def test_reconstructs_the_cited_text_from_its_span(self):
        with _client(_notes_handler) as client:
            sources = resolve_sources(client, [_citation(end=42)])
        assert sources[0].text == "RRF fuses ranked lists rather than scores."
        assert sources[0].span_valid

    def test_a_span_past_the_end_of_the_document_is_invalid(self):
        # The span is what a client highlights. One that does not address real text is a
        # citation a reader cannot follow, whatever the prose says.
        with _client(_notes_handler) as client:
            sources = resolve_sources(client, [_citation(end=9999)])
        assert not sources[0].span_valid
        assert sources[0].text == ""

    def test_an_inverted_span_is_invalid(self):
        with _client(_notes_handler) as client:
            sources = resolve_sources(client, [_citation(start=30, end=10)])
        assert not sources[0].span_valid

    def test_each_document_is_fetched_once(self):
        calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(str(request.url))
            return _notes_handler(request)

        with _client(handler) as client:
            resolve_sources(client, [_citation(1, 0, 5), _citation(2, 6, 10)])
        assert len(calls) == 1

    def test_rendering_numbers_sources_as_the_judge_sees_them(self):
        with _client(_notes_handler) as client:
            sources = resolve_sources(client, [_citation(number=2, end=4)])
        assert render_sources(sources).startswith('[2] (from "hybrid")\nRRF ')


def _api(answer: str, citations: list[dict]):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/ask":
            return httpx.Response(200, json={"content": answer, "citations": citations})
        return _notes_handler(request)

    return handler


QUERY = GoldenQuery("q1", "How are ranked lists combined?", ())


class TestScoring:
    def test_a_reference_to_a_source_that_was_never_offered_is_recorded(self):
        # One source was given; the answer cites a fourth. The number is fabricated, and
        # no judge is needed to know it.
        judge = _stub_judge({"claims": [_claim([4], False, False)], "addresses_question": True})
        with _client(_api("Ranked lists are fused [4].", [_citation()])) as client:
            result = score_answerable(client, judge, QUERY)
        assert result["invalid_citations"] == [4]
        assert result["sources_offered"] == 1

    def test_a_faithful_well_cited_answer_scores_one(self):
        judge = _stub_judge(
            {
                "claims": [_claim([1], True, True), _claim([1], True, True)],
                "addresses_question": True,
            }
        )
        with _client(_api("Fused [1]. Not summed [1].", [_citation()])) as client:
            result = score_answerable(client, judge, QUERY)
        assert result["faithfulness"] == 1.0
        assert result["citation_precision"] == 1.0
        assert result["claim_citation_rate"] == 1.0
        assert result["invalid_citations"] == []
        assert result["spans_valid"]

    def test_a_true_claim_on_the_wrong_source_separates_the_two_metrics(self):
        # The failure this exists to catch: the claim is supported by the material, but
        # not by the source the answer points at. Faithfulness alone reports it as fine.
        judge = _stub_judge({"claims": [_claim([1], False, True)], "addresses_question": True})
        with _client(_api("Reranking is off [1].", [_citation()])) as client:
            result = score_answerable(client, judge, QUERY)
        assert result["faithfulness"] == 1.0
        assert result["citation_precision"] == 0.0

    def test_claims_without_citations_are_excluded_from_precision(self):
        # Precision is over claims that make a reference. An uncited claim is counted by
        # claim_citation_rate instead, so the two failures stay distinguishable.
        judge = _stub_judge(
            {
                "claims": [_claim([1], True, True), _claim([], False, True)],
                "addresses_question": True,
            }
        )
        with _client(_api("Fused [1]. Also this.", [_citation()])) as client:
            result = score_answerable(client, judge, QUERY)
        assert result["citation_precision"] == 1.0
        assert result["claim_citation_rate"] == 0.5

    def test_abstention_is_recorded_for_an_unanswerable_query(self):
        judge = _stub_judge({"abstained": True, "unsupported": False})
        query = GoldenQuery("q028", "How is sharding handled?", (), unanswerable=True)
        with _client(_api("The notes do not cover this.", [])) as client:
            result = score_unanswerable(client, judge, query)
        assert result["abstained"]
        assert not result["unsupported"]


class TestJudgeReplyParsing:
    def test_reads_a_fenced_block(self):
        assert parse_json('```json\n{"abstained": true}\n```') == {"abstained": True}

    def test_reads_bare_json(self):
        assert parse_json('{"abstained": false}') == {"abstained": False}

    def test_a_non_json_reply_raises_rather_than_scoring_zero(self):
        # Blaming the system under test for a fault in its judge would be a silent way to
        # report a regression that did not happen.
        with pytest.raises(ValueError, match="did not return JSON"):
            parse_json("I think the answer is fine.")

    def test_a_json_list_is_rejected(self):
        with pytest.raises(ValueError, match="did not return JSON"):
            parse_json("[1, 2, 3]")

    def test_reads_an_object_followed_by_commentary(self):
        # What ended the first full run, on question 130 of 136: the verdict was correct
        # and a sentence of explanation came after it, which a whole-reply parse rejects.
        reply = '{"abstained": true, "unsupported": false}\n\nNote: the Answer field was empty.'
        assert parse_json(reply) == {"abstained": True, "unsupported": False}

    def test_braces_inside_strings_do_not_end_the_object(self):
        assert parse_json('{"text": "a } brace", "ok": true}') == {"text": "a } brace", "ok": True}

    def test_an_escaped_quote_inside_a_string_is_handled(self):
        assert parse_json(r'{"text": "he said \"hi\"", "ok": true}')["ok"] is True

    def test_an_unterminated_object_raises(self):
        with pytest.raises(ValueError, match="unterminated"):
            parse_json('{"abstained": true')


class TestRunResilience:
    def test_an_empty_answer_is_scored_zero_without_consulting_the_judge(self):
        # An empty answer has nothing to decompose. Sending it to a judge invites a reply
        # about the empty field rather than a verdict, which is what broke the first run.
        def judge(prompt: str) -> str:
            raise AssertionError("the judge should not be called for an empty answer")

        judge.name = "stub"  # type: ignore[attr-defined]
        with _client(_api("", [_citation()])) as client:
            result = score_answerable(client, judge, QUERY)
        assert result["empty"]
        assert result["faithfulness"] == 0.0
        assert result["addresses_question"] is False

    def test_one_unusable_judge_reply_costs_its_query_not_the_run(self):
        calls = {"n": 0}

        def judge(prompt: str) -> str:
            calls["n"] += 1
            if calls["n"] == 1:
                return "no json here at all"
            return json.dumps({"claims": [_claim([1], True, True)], "addresses_question": True})

        judge.name = "stub"  # type: ignore[attr-defined]
        queries = [GoldenQuery("q1", "first", ()), GoldenQuery("q2", "second", ())]
        with _client(_api("Fused [1].", [_citation()])) as client:
            report = evaluate(client, judge, queries)

        assert report["queries_answerable"] == 1
        assert [f["id"] for f in report["failures"]] == ["q1"]
        assert report["metrics"]["faithfulness"] == 1.0


THREE_QUERIES = [
    GoldenQuery("q1", "first", ()),
    GoldenQuery("q2", "second", ()),
    GoldenQuery("q3", "third", ()),
]


class TestCheckpointing:
    """Resuming a part-finished run.

    A full run is a few hundred model calls against a paid endpoint. Losing one to a
    dropped connection or a closed laptop costs the whole set again, so each query is
    committed as it is scored. These cover what that commit has to guarantee: work already
    paid for is not repeated, and work from a different run is never mistaken for it.
    """

    @staticmethod
    def _counting_api(asked: list[str]):
        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/api/ask":
                asked.append(json.loads(request.content)["question"])
                return httpx.Response(
                    200, json={"content": "Fused [1].", "citations": [_citation()]}
                )
            return _notes_handler(request)

        return handler

    @staticmethod
    def _judge():
        return _stub_judge({"claims": [_claim([1], True, True)], "addresses_question": True})

    def test_a_resumed_run_does_not_pay_for_a_query_it_already_scored(self, tmp_path):
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client, self._judge(), THREE_QUERIES[:2], checkpoint=checkpoint, golden="golden"
            )
        assert asked == ["first", "second"]

        asked.clear()
        with _client(self._counting_api(asked)) as client:
            report = evaluate(
                client, self._judge(), THREE_QUERIES, checkpoint=checkpoint, golden="golden"
            )

        assert asked == ["third"], "the two already scored are restored, not re-asked"
        assert [q["id"] for q in report["per_query"]] == ["q1", "q2", "q3"]

    def test_a_checkpoint_from_the_other_path_is_not_resumed_into_this_one(self, tmp_path):
        # The failure this guards against is silent and expensive: the loop's answers
        # restored into a single-pass run produce a comparison of one path against itself,
        # at full price, with nothing in the numbers to show it happened.
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client,
                self._judge(),
                THREE_QUERIES[:2],
                agent=True,
                checkpoint=checkpoint,
                golden="golden",
            )
        assert asked == ["first", "second"]

        asked.clear()
        with _client(self._counting_api(asked)) as client:
            evaluate(
                client,
                self._judge(),
                THREE_QUERIES[:2],
                agent=False,
                checkpoint=checkpoint,
                golden="golden",
            )

        assert asked == ["first", "second"], "scored again, on the path that was asked for"

    def test_a_checkpoint_over_a_different_golden_set_is_not_resumed_either(self, tmp_path):
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client, self._judge(), THREE_QUERIES[:1], checkpoint=checkpoint, golden="golden"
            )
        asked.clear()

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client,
                self._judge(),
                THREE_QUERIES[:1],
                checkpoint=checkpoint,
                golden="golden-multihop",
            )

        assert asked == ["first"]

    def test_a_half_written_final_line_costs_its_query_not_the_file(self, tmp_path):
        # What a process killed mid-write leaves behind. The lines before it were flushed
        # whole and are the whole point of having the file.
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []

        with _client(self._counting_api(asked)) as client:
            evaluate(client, self._judge(), THREE_QUERIES, checkpoint=checkpoint, golden="golden")

        lines = checkpoint.read_text().splitlines()
        assert len(lines) == 4, "a header and one line per scored query"
        checkpoint.write_text("\n".join(lines[:-1]) + "\n" + lines[-1][:20])

        asked.clear()
        with _client(self._counting_api(asked)) as client:
            report = evaluate(
                client, self._judge(), THREE_QUERIES, checkpoint=checkpoint, golden="golden"
            )

        assert asked == ["third"], "only the query whose line was truncated is re-asked"
        assert report["queries_answerable"] == 3

    def test_a_second_resume_reuses_what_the_first_one_added(self, tmp_path):
        # The truncated tail has to be cleared rather than left in place. Appended after
        # one, later lines are read as coming after a break and skipped, so a run that
        # died twice would keep paying for the same queries.
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client, self._judge(), THREE_QUERIES[:1], checkpoint=checkpoint, golden="golden"
            )
        checkpoint.write_text(checkpoint.read_text() + '{"kind": "answerable", "rec')

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client, self._judge(), THREE_QUERIES[:2], checkpoint=checkpoint, golden="golden"
            )
        asked.clear()

        with _client(self._counting_api(asked)) as client:
            evaluate(client, self._judge(), THREE_QUERIES, checkpoint=checkpoint, golden="golden")

        assert asked == ["third"]

    def test_a_query_that_changed_kind_is_scored_again_rather_than_restored(self, tmp_path):
        # An id can move between the two halves of the set when the golden file is edited.
        # Its old record has none of the fields the other half aggregates, so restoring it
        # would fail the run on a KeyError partway through rather than at the start.
        checkpoint = tmp_path / "run.json.partial"
        asked: list[str] = []
        judge = _stub_judge(
            {
                "claims": [_claim([1], True, True)],
                "addresses_question": True,
                "abstained": True,
                "unsupported": False,
            }
        )

        with _client(self._counting_api(asked)) as client:
            evaluate(
                client,
                judge,
                [GoldenQuery("q1", "first", ())],
                checkpoint=checkpoint,
                golden="golden",
            )
        asked.clear()

        moved = [GoldenQuery("q1", "first", (), unanswerable=True)]
        with _client(self._counting_api(asked)) as client:
            report = evaluate(client, judge, moved, checkpoint=checkpoint, golden="golden")

        assert asked == ["first"]
        assert report["per_unanswerable"][0]["abstained"] is True

    def test_no_checkpoint_is_written_when_none_was_asked_for(self, tmp_path):
        asked: list[str] = []
        with _client(self._counting_api(asked)) as client:
            evaluate(client, self._judge(), THREE_QUERIES[:1], golden="golden")
        assert list(tmp_path.iterdir()) == []


class TestRunOutput:
    """Where a run is written, which is what makes a path comparison scriptable."""

    def test_writes_where_it_is_told_rather_than_to_a_timestamped_name(self, tmp_path, monkeypatch):
        # Two halves of a comparison over the same golden set differ only by their
        # timestamp otherwise, and a script picking them apart by modification time is one
        # restart away from comparing a run against itself.
        import ml_service.evals.generation as generation

        out = tmp_path / "nested" / "single-pass.json"
        monkeypatch.setattr(generation, "evaluate", lambda *a, **k: {"metrics": {}})
        monkeypatch.setattr(generation, "print_report", lambda report: None)
        monkeypatch.setattr(generation, "default_judge", lambda *a, **k: None)
        monkeypatch.setattr(generation, "load_golden_set", lambda directory: [object()])
        monkeypatch.setattr(generation.httpx, "Client", lambda **kwargs: _NullClient())

        assert generation.main(["--out", str(out)]) == 0

        # The parent is created, so a caller may name a directory that does not exist yet.
        assert json.loads(out.read_text())["metrics"] == {}


class TestCheckpointPath:
    """Where the part-finished run is kept, and when it is cleared."""

    @staticmethod
    def _stub(monkeypatch, seen: dict, report: dict):
        import ml_service.evals.generation as generation

        def evaluate(client, judge, queries, limit=None, agent=False, checkpoint=None, golden=""):
            seen["checkpoint"] = checkpoint
            return report

        monkeypatch.setattr(generation, "evaluate", evaluate)
        monkeypatch.setattr(generation, "print_report", lambda report: None)
        monkeypatch.setattr(generation, "default_judge", lambda *a, **k: None)
        monkeypatch.setattr(generation, "load_golden_set", lambda directory: [object()])
        monkeypatch.setattr(generation.httpx, "Client", lambda **kwargs: _NullClient())
        return generation

    def test_defaults_beside_the_run_it_belongs_to(self, tmp_path, monkeypatch):
        # Beside the run rather than in one fixed place, so the two paths of a comparison
        # cannot share a checkpoint and resume into each other.
        seen: dict = {}
        generation = self._stub(monkeypatch, seen, {"metrics": {}})
        out = tmp_path / "single-pass.json"

        assert generation.main(["--out", str(out)]) == 0
        assert seen["checkpoint"] == tmp_path / "single-pass.json.partial"

    def test_no_checkpoint_switches_it_off(self, tmp_path, monkeypatch):
        seen: dict = {}
        generation = self._stub(monkeypatch, seen, {"metrics": {}})

        assert generation.main(["--out", str(tmp_path / "a.json"), "--no-checkpoint"]) == 0
        assert seen["checkpoint"] is None

    def test_a_finished_run_clears_its_checkpoint(self, tmp_path, monkeypatch):
        # Left behind, it offers a resume to a run that has nothing left to do.
        seen: dict = {}
        generation = self._stub(monkeypatch, seen, {"metrics": {}})
        partial = tmp_path / "a.json.partial"
        partial.write_text('{"golden": "golden", "path": "single-pass"}\n')

        assert generation.main(["--out", str(tmp_path / "a.json")]) == 0
        assert not partial.exists()

    def test_a_run_with_unscored_queries_keeps_its_checkpoint(self, tmp_path, monkeypatch):
        # A failure means some query has no record. Clearing the file would make the rerun
        # that fixes it pay for every query again.
        seen: dict = {}
        generation = self._stub(
            monkeypatch, seen, {"metrics": {}, "failures": [{"id": "q7", "error": "boom"}]}
        )
        partial = tmp_path / "a.json.partial"
        partial.write_text('{"golden": "golden", "path": "single-pass"}\n')

        assert generation.main(["--out", str(tmp_path / "a.json")]) == 0
        assert partial.exists()


class _NullClient:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False
