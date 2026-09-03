"""Golden dataset loading, validation, and relevance judgement.

The loader rejects malformed entries loudly. An unlabelled query silently scores zero,
which drags the reported average down and looks like a retrieval regression — so the
failure has to happen at load time, not at scoring time.
"""

from pathlib import Path

import pytest

from ml_service.evals.dataset import (
    GoldenQuery,
    RelevanceRule,
    corpus_documents,
    judge,
    load_golden_set,
    normalise,
)

REPO_ROOT = Path(__file__).resolve().parents[2]


def write(tmp_path: Path, *lines: str) -> Path:
    (tmp_path / "set.jsonl").write_text("\n".join(lines) + "\n")
    return tmp_path


class TestLoading:
    def test_loads_a_query(self, tmp_path: Path) -> None:
        d = write(tmp_path, '{"id":"q1","query":"why","relevant":[{"doc":"a","contains":"x"}]}')

        queries = load_golden_set(d)

        assert len(queries) == 1
        assert queries[0].id == "q1"
        assert queries[0].relevant[0] == RelevanceRule(doc="a", contains="x")

    def test_skips_blank_and_comment_lines(self, tmp_path: Path) -> None:
        d = write(tmp_path, "", "// a note", '{"id":"q1","query":"q","relevant":[{"doc":"a"}]}')

        assert len(load_golden_set(d)) == 1

    def test_rejects_duplicate_ids(self, tmp_path: Path) -> None:
        d = write(
            tmp_path,
            '{"id":"q1","query":"a","relevant":[{"doc":"a"}]}',
            '{"id":"q1","query":"b","relevant":[{"doc":"b"}]}',
        )

        with pytest.raises(ValueError, match="duplicate query id"):
            load_golden_set(d)

    def test_rejects_unlabelled_query(self, tmp_path: Path) -> None:
        d = write(tmp_path, '{"id":"q1","query":"q","relevant":[]}')

        with pytest.raises(ValueError, match="not marked unanswerable"):
            load_golden_set(d)

    def test_rejects_unanswerable_query_that_lists_documents(self, tmp_path: Path) -> None:
        d = write(tmp_path, '{"id":"q1","query":"q","relevant":[{"doc":"a"}],"unanswerable":true}')

        with pytest.raises(ValueError, match="marked unanswerable"):
            load_golden_set(d)

    def test_reports_the_offending_line(self, tmp_path: Path) -> None:
        d = write(tmp_path, '{"id":"q1","query":"q","relevant":[{"doc":"a"}]}', "{not json")

        with pytest.raises(ValueError, match=":2:"):
            load_golden_set(d)


class TestRelevanceCounting:
    def test_counts_distinct_documents(self) -> None:
        # Two rules on one document must not inflate the recall denominator.
        query = GoldenQuery(
            id="q1",
            query="q",
            relevant=(RelevanceRule("a", "x"), RelevanceRule("a", "y"), RelevanceRule("b")),
        )

        assert query.total_relevant == 2


class TestJudge:
    query = GoldenQuery(id="q1", query="q", relevant=(RelevanceRule("hybrid", "fusion"),))

    def test_matches_document_and_substring(self) -> None:
        hits = [{"title": "hybrid", "snippet": "merged with Reciprocal Rank Fusion"}]

        assert judge(self.query, hits) == [1]

    def test_substring_match_is_case_insensitive(self) -> None:
        hits = [{"title": "hybrid", "snippet": "RECIPROCAL RANK FUSION"}]

        assert judge(self.query, hits) == [1]

    def test_phrase_matches_across_a_line_break(self) -> None:
        # Source documents are hard-wrapped, and a phrase that reads as one line can
        # span two in the file. Matching on the raw text would score this zero and
        # look like a retrieval miss.
        query = GoldenQuery(
            id="q3", query="q", relevant=(RelevanceRule("hybrid", "ranks rather than scores"),)
        )
        hits = [
            {
                "title": "hybrid",
                "snippet": "Fusion operates on ranks rather\nthan scores, which avoids",
            }
        ]

        assert judge(query, hits) == [1]

    def test_right_document_without_the_substring_is_not_relevant(self) -> None:
        hits = [{"title": "hybrid", "snippet": "The dense channel embeds the query"}]

        assert judge(self.query, hits) == [0]

    def test_right_substring_in_the_wrong_document_is_not_relevant(self) -> None:
        hits = [{"title": "cost-model", "snippet": "fusion"}]

        assert judge(self.query, hits) == [0]

    def test_preserves_rank_order(self) -> None:
        hits = [
            {"title": "cost-model", "snippet": "unrelated"},
            {"title": "hybrid", "snippet": "fusion happens here"},
        ]

        assert judge(self.query, hits) == [0, 1]

    def test_rule_without_substring_matches_any_chunk_of_the_document(self) -> None:
        query = GoldenQuery(id="q2", query="q", relevant=(RelevanceRule("hybrid"),))

        assert judge(query, [{"title": "hybrid", "snippet": "anything at all"}]) == [1]


SUITES = ("golden", "golden-known-item", "golden-known-item-heldout", "golden-multihop")


class TestCommittedGoldenSet:
    """The committed sets must stay consistent with the committed corpus."""

    @pytest.mark.parametrize("suite", SUITES)
    def test_every_referenced_document_exists(self, suite: str) -> None:
        queries = load_golden_set(REPO_ROOT / "evals" / suite)
        corpus = set(corpus_documents(REPO_ROOT / "evals" / "corpus"))

        referenced = {rule.doc for q in queries for rule in q.relevant}

        assert referenced <= corpus, f"{suite} references missing documents: {referenced - corpus}"

    @pytest.mark.parametrize("suite", SUITES)
    def test_every_phrase_appears_in_its_document(self, suite: str) -> None:
        # A phrase that appears nowhere in its document can never be matched, so the
        # query scores zero no matter how well retrieval performs. That looks like a
        # retrieval failure in the metrics, which is the one thing this harness must
        # not get wrong.
        queries = load_golden_set(REPO_ROOT / "evals" / suite)
        corpus = {
            slug: normalise(text)
            for slug, text in corpus_documents(REPO_ROOT / "evals" / "corpus").items()
        }

        unmatchable = [
            (q.id, rule.doc, rule.contains)
            for q in queries
            for rule in q.relevant
            if rule.contains and normalise(rule.contains) not in corpus.get(rule.doc, "")
        ]

        assert not unmatchable, f"{suite} phrases absent from their documents: {unmatchable}"

    def test_every_multihop_query_needs_more_than_one_document(self) -> None:
        # The point of the slice. A question answerable from one document measures the
        # same thing the semantic set already measures, and would dilute a comparison
        # between one retrieval pass and several rather than sharpen it.
        queries = load_golden_set(REPO_ROOT / "evals" / "golden-multihop")

        single = [q.id for q in queries if q.total_relevant < 2]

        assert queries, "the multi-hop slice is empty"
        assert not single, f"answerable from one document: {single}"

    def test_contains_unanswerable_queries(self) -> None:
        # A set with no unanswerable queries cannot detect a system that always answers.
        queries = load_golden_set(REPO_ROOT / "evals" / "golden")

        assert any(q.unanswerable for q in queries)
