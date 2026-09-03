"""Whether both documents a two-hop question needs reach the model that answers it.

Recall at a cutoff answers a different question than it appears to on this slice. Every
query here has two relevant documents, so retrieving one of them scores 0.5 where a
single-document query retrieving its one document scores 1.0 — a lower number that is
partly the arithmetic rather than a worse result. What decides whether the question can be
answered at all is simpler and is not a mean: are both documents in the ten passages the
generator is handed.

The report also says where a missing document actually ranks, because that is what
separates two different problems. A document at rank 11 is a depth or ranking question. A
document the cross-encoder scored and still placed outside the top ten is a document whose
relevance depends on the other one — which no scorer reading (query, passage) pairs one at
a time can see, however deep it is given.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import httpx

GOLDEN = Path(__file__).resolve().parents[3].parent / "evals" / "golden-multihop"

#: What the API returns to the generator, and the cutoff that therefore decides the answer.
GENERATOR_SEES = 10

#: Deep enough to show where a missing document sits without being the whole corpus.
PROBE_DEPTH = 50


@dataclass(frozen=True)
class Question:
    """A query and the documents an answer to it has to rest on."""

    id: str
    query: str
    documents: tuple[str, ...]


@dataclass(frozen=True)
class Missing:
    """A document that did not reach the generator, and where it was instead."""

    question: str
    document: str
    rank_at_depth: int | None
    rank_reranked: int | None


def load(directory: Path) -> list[Question]:
    questions: list[Question] = []
    for path in sorted(directory.glob("*.jsonl")):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            # Deduplicated, and that is not cosmetic. A question may cite two phrases
            # from the same document — M034 does — and counting the entries would treat
            # that document as two of the sources it needs, marking the question complete
            # while a genuinely missing second document went on being reported as missing.
            # The requirement is distinct documents, in the order the slice lists them.
            documents = list(dict.fromkeys(r["doc"] for r in row["relevant"]))
            questions.append(Question(id=row["id"], query=row["query"], documents=tuple(documents)))
    return questions


def documents_from_agent(client: httpx.Client, query: str) -> list[str]:
    """The documents the agent loop actually put in front of the model.

    Asked through ``/api/ask?agent=true`` rather than through search, because what the loop
    retrieves is the loop's own decision — it reads the first result and chooses the second
    query. The citations the answer carries are the ledger of every passage the tool
    returned across its searches, not only the ones the answer went on to cite, so this
    reports what the model was shown.

    No judge runs. Whether the loop *found* the missing document is a question about
    retrieval, and answering it does not need anything scoring the prose — which is what
    makes this the cheap way to ask the one thing worth asking first.
    """
    body = (
        client.post("/api/ask", params={"agent": "true"}, json={"question": query}, timeout=300.0)
        .raise_for_status()
        .json()
    )
    ordered: list[str] = []
    for citation in body.get("citations", []):
        if citation["title"] not in ordered:
            ordered.append(citation["title"])
    return ordered


def documents_for(client: httpx.Client, query: str, limit: int, rerank: bool) -> list[str]:
    """Search, reduced to distinct documents in rank order.

    Ranked by document rather than by chunk because the question is which documents an
    answer can draw on. Two chunks of one document are one source, and counting them
    twice would report a top ten that is really a top six.
    """
    params = f"?q={urllib.parse.quote(query)}&limit={limit}"
    if rerank:
        params += "&rerank=true"
    body = client.get(f"/api/search{params}").raise_for_status().json()
    hits = body["results"] if isinstance(body, dict) and "results" in body else body

    ordered: list[str] = []
    for hit in hits:
        if hit["title"] not in ordered:
            ordered.append(hit["title"])
    return ordered


def rank_of(documents: list[str], wanted: str) -> int | None:
    return documents.index(wanted) + 1 if wanted in documents else None


def evaluate(
    client: httpx.Client, questions: list[Question], agent: bool = False
) -> tuple[Counter[int], list[Missing]]:
    found_counts: Counter[int] = Counter()
    missing: list[Missing] = []

    for question in questions:
        if agent:
            seen = documents_from_agent(client, question.query)
        else:
            seen = documents_for(client, question.query, GENERATOR_SEES, rerank=False)
        present = [d for d in question.documents if d in seen]
        # Keyed on what is still absent rather than on what was found, because the slice
        # is not uniform: 35 questions need two documents and one needs three, so a count
        # of two means "complete" for most of them and "one short" for that one.
        found_counts[len(question.documents) - len(present)] += 1

        for document in question.documents:
            if document in seen:
                continue
            # Where it sits under the plain search, which is the comparison that makes a
            # loop's miss readable: a document the loop did not reach is different from
            # one nothing reaches.
            deep = documents_for(client, question.query, PROBE_DEPTH, rerank=False)
            reranked = documents_for(client, question.query, GENERATOR_SEES, rerank=True)
            missing.append(
                Missing(
                    question=question.id,
                    document=document,
                    rank_at_depth=rank_of(deep, document),
                    rank_reranked=rank_of(reranked, document),
                )
            )
    return found_counts, missing


def report(
    questions: list[Question], counts: Counter[int], missing: list[Missing], via: str
) -> None:
    total = len(questions)
    complete = counts[0]
    needed = Counter(len(q.documents) for q in questions)
    shape = ", ".join(f"{n} needing {k}" for k, n in sorted(needed.items()))

    print(f"\n  Two-hop retrieval — {total} questions ({shape} documents)")
    print(f"  {via}")
    print("  " + "-" * 52)
    for short in sorted(counts):
        label = "complete" if short == 0 else f"{short} document(s) short"
        print(f"    {counts[short]:2d}  {label}")
    print(f"    every source present: {complete}/{total} = {complete / total:.1%}")
    print("  " + "-" * 52)

    if not missing:
        return

    print("\n  Where each missing document was instead:")
    print(f"    {'question':10}{'document':32}{f'rank@{PROBE_DEPTH}':>10}{'reranked':>11}")
    for m in missing:
        deep = str(m.rank_at_depth) if m.rank_at_depth else f"beyond {PROBE_DEPTH}"
        rr = str(m.rank_reranked) if m.rank_reranked else "not in 10"
        print(f"    {m.question:10}{m.document:32}{deep:>10}{rr:>11}")

    reachable = sum(1 for m in missing if m.rank_at_depth is not None)
    recovered = sum(1 for m in missing if m.rank_reranked is not None)
    print(
        f"\n    {reachable} of {len(missing)} are within the first {PROBE_DEPTH}, "
        "so they are ranked low rather than unreachable."
    )
    print(
        f"    {recovered} of {len(missing)} are recovered by the cross-encoder. The rest it "
        "scored\n    and still placed outside the top ten, which is what a document relevant "
        "only\n    in the light of the other one looks like to a scorer reading one at a time."
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://localhost:8080", help="base URL of the API")
    parser.add_argument("--golden", type=Path, default=GOLDEN, help="the multi-hop slice")
    parser.add_argument(
        "--agent",
        action="store_true",
        help="ask through the agent loop instead of one search. Costs model calls; no "
        "judge runs, since what is being asked is which documents were retrieved.",
    )
    parser.add_argument(
        "--only",
        help="just these query ids, comma-separated — the handful worth paying for",
    )
    args = parser.parse_args(argv)

    questions = load(args.golden)
    if not questions:
        print(f"no questions found in {args.golden}", file=sys.stderr)
        return 1

    if args.only:
        wanted = [q.strip() for q in args.only.split(",") if q.strip()]
        by_id = {q.id: q for q in questions}
        absent = [q for q in wanted if q not in by_id]
        if absent:
            print(f"no such query id: {', '.join(absent)}", file=sys.stderr)
            return 1
        questions = [by_id[q] for q in wanted]

    with httpx.Client(base_url=args.api, timeout=300.0) as client:
        counts, missing = evaluate(client, questions, agent=args.agent)
    via = (
        "retrieved by the agent loop, across whatever searches it chose"
        if args.agent
        else f"top {GENERATOR_SEES} of one search, which is what the generator is handed"
    )
    report(questions, counts, missing, via)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
