"""Golden dataset loading and relevance judgement.

The golden set is JSONL, one query per line:

    {"id": "q001",
     "query": "How are ranked lists combined?",
     "relevant": [{"doc": "hybrid-retrieval", "contains": "Reciprocal Rank Fusion"}],
     "unanswerable": false}

Relevance is expressed against a **document slug plus an optional phrase**, never a
chunk ID. Chunk IDs are generated at ingest and change whenever the chunking strategy
changes — which is precisely the kind of change this harness exists to measure, so a
golden set keyed on them would invalidate itself on the runs that matter most.

``unanswerable`` marks queries the corpus genuinely cannot answer. They carry no
relevant documents and are excluded from ranking metrics; their purpose is the
generation eval, where the correct behaviour is to decline rather than to invent.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

__all__ = [
    "GoldenQuery",
    "RelevanceRule",
    "corpus_documents",
    "judge",
    "load_golden_set",
    "normalise",
]


def corpus_documents(directory: Path) -> dict[str, str]:
    """Maps slug to text for every corpus document, keyed on the filename stem.

    ``README.md`` describes the corpus rather than belonging to it, so it is excluded
    here and by the seeder. Ingesting it would add a distractor about the eval harness
    itself, competing with the eval-methodology document at retrieval time.
    """
    return {
        path.stem: path.read_text()
        for path in sorted(directory.glob("*.md"))
        if path.name != "README.md"
    }


def normalise(text: str) -> str:
    """Lower-cases and collapses every run of whitespace to a single space.

    Source documents are hard-wrapped, so a phrase that reads as one line in prose can
    span two in the file. Comparing normalised text lets the golden set describe what a
    document says rather than where it happens to wrap.
    """
    return " ".join(text.split()).lower()


@dataclass(frozen=True)
class RelevanceRule:
    """One condition under which a retrieved chunk counts as relevant."""

    doc: str
    contains: str | None = None

    def matches(self, doc_slug: str, chunk_text: str) -> bool:
        if doc_slug != self.doc:
            return False
        if self.contains is None:
            return True
        return normalise(self.contains) in normalise(chunk_text)


@dataclass(frozen=True)
class GoldenQuery:
    id: str
    query: str
    relevant: tuple[RelevanceRule, ...] = field(default_factory=tuple)
    unanswerable: bool = False

    @property
    def total_relevant(self) -> int:
        """Number of distinct documents expected to contain an answer.

        Counted per document rather than per rule so that two rules pointing at the same
        document do not inflate the recall denominator.
        """
        return len({rule.doc for rule in self.relevant})


def load_golden_set(directory: Path) -> list[GoldenQuery]:
    """Loads every ``*.jsonl`` file in ``directory``, sorted for reproducible runs."""
    queries: list[GoldenQuery] = []
    seen: set[str] = set()

    for path in sorted(directory.glob("*.jsonl")):
        for lineno, line in enumerate(path.read_text().splitlines(), start=1):
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{lineno}: invalid JSON — {exc}") from exc

            query_id = raw["id"]
            if query_id in seen:
                raise ValueError(f"{path}:{lineno}: duplicate query id {query_id!r}")
            seen.add(query_id)

            rules = tuple(
                RelevanceRule(doc=r["doc"], contains=r.get("contains"))
                for r in raw.get("relevant", [])
            )
            unanswerable = bool(raw.get("unanswerable", False))

            if unanswerable and rules:
                raise ValueError(
                    f"{path}:{lineno}: {query_id} is marked unanswerable "
                    f"but lists relevant documents"
                )
            if not unanswerable and not rules:
                raise ValueError(
                    f"{path}:{lineno}: {query_id} has no relevant documents and is not "
                    f"marked unanswerable — an unlabelled query silently scores zero"
                )

            queries.append(
                GoldenQuery(
                    id=query_id, query=raw["query"], relevant=rules, unanswerable=unanswerable
                )
            )

    return queries


def judge(query: GoldenQuery, results: list[dict[str, str]], level: str = "passage") -> list[int]:
    """Reduces a ranked result list to a binary relevance vector.

    ``results`` are search hits in rank order, each carrying at least ``title`` (the
    document slug) and ``snippet``.

    ``level`` selects what counts as a hit. At ``passage`` level — the default, and what
    every recorded baseline uses — a chunk is relevant only if it contains the labelled
    phrase, so a chunk from the right document that does not answer the query scores
    zero. That is the right standard for a system whose chunks become LLM context.

    At ``document`` level the phrase is ignored and any chunk of a relevant document
    counts. It exists to separate two failures that the passage score merges: retrieving
    the wrong document, and retrieving the right document's wrong passage. The two are
    indistinguishable in a single number, and they have different remedies — which
    matters whenever a chunking change alters how many chunks a document has.
    """
    if level not in ("passage", "document"):
        raise ValueError(f"unknown relevance level {level!r}")
    return [
        int(
            any(
                rule.matches(hit.get("title", ""), hit.get("snippet", ""))
                if level == "passage"
                else rule.doc == hit.get("title", "")
                for rule in query.relevant
            )
        )
        for hit in results
    ]
