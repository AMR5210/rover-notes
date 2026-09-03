#!/usr/bin/env python3
"""Fills a running instance with a small research library, for demonstrations.

What this is for: screenshots, recordings, and a first look at the interface with
something in it. It is not the evaluation corpus and not a test fixture. ``evals/``
holds the labelled documents retrieval quality is scored against, and the eval harness
refuses to score a corpus whose document count is not the one it seeded — so point this
at a development database rather than one you are about to measure.

The notes are written as somebody's working notes on this system and the literature
around it. Every figure in them is one this repository measured and records in
docs/RESULTS.md, rather than a number invented to look plausible.

Two topics, adjacent rather than unrelated: how passages are found, and whether the
answer built from them is any good. They are separate concerns that one library holds
at once, which is the case topics exist for. A note is left unfiled as well, because a
real library has those too.

Usage::

    demo/seed.py                          # against http://localhost:8080
    API=http://localhost:9000 demo/seed.py

Idempotent: a topic that already exists is reused, and a note whose title is already
present is left alone, so running it twice does not double the library.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("API", "http://localhost:8080")


def call(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    """One request. Returns the status and the decoded body, rather than raising on 4xx.

    A conflict is an ordinary outcome here — it is how the script learns a topic already
    exists — so the error status is returned to the caller instead of ending the run.
    """
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        f"{API}{path}",
        data=data,
        method=method,
        headers={"content-type": "application/json"} if data else {},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, (json.loads(raw) if raw else None)


def topic(name: str) -> str:
    """The id of the topic with this name, creating it if it is not there yet."""
    status, created = call("POST", "/api/topics", {"name": name})
    if status == 201:
        return created["id"]

    # 409: somebody — probably an earlier run of this script — already made it.
    _, existing = call("GET", "/api/topics")
    for held in existing:
        if held["name"] == name:
            return held["id"]
    raise SystemExit(f"could not create or find the topic {name!r}")


def note(topic_id: str | None, title: str, content: str, present: set[str]) -> None:
    if title in present:
        print(f"    = {title}")
        return
    status, _ = call(
        "POST", "/api/notes", {"title": title, "content": content, "topicId": topic_id}
    )
    if status != 201:
        raise SystemExit(f"could not add {title!r}: {status}")
    print(f"    + {title}")


RETRIEVAL_NOTES = [
    (
        "Reciprocal rank fusion, and why k is 60",
        """RRF combines ranked lists by position rather than by score:

    score(d) = sum over channels of 1 / (k + rank(d))

The point is that it never has to reconcile two scoring functions. A cosine similarity
and a ts_rank value have different scales, different distributions and no shared zero,
so adding them directly needs a calibration step that has to be re-derived whenever
either side changes. Ranks have none of those problems.

k = 60 comes from Cormack, Clarke and Buettcher (2009). Swept it over 10, 30, 60 and
100 on this corpus: 30, 60 and 100 give identical per-query scores, and 10 moves three
queries by an amount the set is too small to resolve. The published value stands, but
on this collection the parameter is not doing much work.

Fusion is not free. Giving both channels the same text makes their rankings correlate,
and correlated rankings have less left to fuse.""",
    ),
    (
        "Postgres ranks, but does not compute IDF",
        """ts_rank and ts_rank_cd are both IMMUTABLE, which means they cannot see corpus
statistics. No document frequency, so no IDF, so no BM25.

That has a consequence worth writing down: a term appearing in every document adds
matching mass without adding discrimination, because nothing discounts it. Which is
exactly why prefixing every chunk with a description of its document hurt the lexical
channel here while helping the dense one.

ts_rank_cd grows linearly in the number of occurrences; ts_rank saturates. Switching
between them was worth +0.0399 nDCG@10 (CI +0.0129 to +0.0700), because the linear
version ranked a chunk matching one query term ten times above a chunk matching all
three terms once each.

The lexical channel alone scores 0.5798 nDCG@10 on SciFact, against BEIR's published
0.665 for BM25. The gap is the missing IDF.""",
    ),
    (
        "Late interaction: MaxSim, and what it costs",
        """A bi-encoder puts a whole passage in one vector, so a rare term the question turns
on is averaged into the general topic around it. Late interaction keeps one vector per
token and scores a pair by MaxSim: for each query token take its best match among the
passage tokens, then sum those maxima.

On SciFact this is +0.0510 nDCG@10 (CI +0.0291 to +0.0734, 85 queries better and 27
worse of 300). recall@20 moved as well, which is the part worth noticing — a reranker
only reorders the list it is handed, so that gain came from passages already retrieved
and ranked too low to count.

The cost is real: 7.7 s at p95, against 2.2 s for a cross-encoder and a 150 ms
retrieval budget. Published systems avoid this by storing token vectors at ingest
rather than encoding candidates per query, which turns a latency problem into a storage
one — the vector store multiplies by the token count of the corpus.

The model here is mxbai-edge-colbert-v0-32m, chosen over the stronger 149M
GTE-ModernColBERT-v1 because it has to score 40 passages on the same CPU that is
serving the request. Given a GPU, the larger model is the better choice.""",
    ),
    (
        "Identifier queries belong to the lexical channel",
        """Embeddings compress a rare token into the general topic around it, which is
precisely wrong for a query that is a rare token. On the identifier slice: dense
averages 0.6965 nDCG@10, lexical 0.9710.

Routing those queries to lexical is worth +0.1573 on held-out identifiers (CI +0.1069
to +0.2105, 30 better and 2 worse of 93). The cost on the semantic set is +0.0016,
where the rule fires on 2 of 128 queries, so it is close to free.

The held-out suite exists because the first measurement was suspect: the identifiers
had been chosen by the same generator that wrote the queries, so a gain might have held
only for tokens like those. Built a second suite from every corpus-unique identifier
the first did not take. Measured there, the effect was larger rather than smaller.""",
    ),
    (
        "ef_search at 100 is not worth it here",
        """0.6843 against 0.6804 nDCG@10 on SciFact, at nearly double the p95. Within noise
for a measurable cost, so not adopted.

Worth revisiting if the corpus grows by an order of magnitude. HNSW recall degrades as
the graph grows, so a parameter doing nothing at 5,183 documents may well matter at
500,000.""",
    ),
]

EVALUATION_NOTES = [
    (
        "Comparing means is the wrong test",
        """Two systems, one corpus, and a mean nDCG@10 apiece tells you almost nothing. The
mean moves when a handful of queries move a long way, and that is indistinguishable
from the mean moving because the system is better everywhere.

Used here instead: a randomisation test over per-query differences, with a bootstrap
confidence interval. A change is adopted when the 95% interval excludes zero. Both
halves matter — the interval catches a change whose gain is real but smaller than
run-to-run variation, and it refuses one that moved the average through three queries
while making forty worse.

Several changes did not clear that bar and are recorded anyway. A technique that does
not help on this workload is as useful to know about as one that does.""",
    ),
    (
        "Faithfulness, citation precision, abstention",
        """Three properties, judged separately, because an answer can pass one and fail
another.

Faithfulness: does each sentence follow from the retrieved passages. Measures
invention.

Citation precision: does each citation support the sentence it is attached to. Measures
whether the references are decorative — a model can write a true sentence and attach it
to the wrong passage, and only this catches that.

Abstention: of the questions the corpus cannot answer, how many are declined. A count
rather than a rate, because the set is small enough that every case is worth reading.

Measured: 0.9688 faithfulness and 0.9723 citation precision with Sonnet 5, 8 of 8
declined. Haiku 4.5 gives 0.9289 and 0.9438, also 8 of 8, at roughly a quarter of the
cost per answer.""",
    ),
    (
        "A collection that cannot separate two systems",
        """The 42-document corpus has recall@20 of 1.0000. Every relevant passage is already
in the candidate list, so any two rerankers are reordering a list that already contains
the answer. Late interaction against a cross-encoder there: +0.0098, interval spanning
zero, 90 of 128 queries unchanged.

That is not evidence the two are equivalent. It is evidence the set cannot tell. The
same comparison on SciFact, where there is room to be wrong, gives +0.0510 with all
three intervals excluding zero.

The lesson is about the collection rather than the models. A corpus written to catch
regressions is not automatically one that can rank two good systems, and reading a null
result from it as equivalence is how a worse system gets adopted.""",
    ),
    (
        "Why SciFact carries the weight",
        """5,183 abstracts and 300 claims, from BEIR. Written by other people and labelled by
other people, which is the property that matters: the in-house corpus was written by
whoever also wrote the queries, and that closes a loop you cannot see from inside.

It is also the only collection here large enough for the vector index to be exercised.
Below roughly 10,000 vectors the planner prefers an exact scan, so a 42-document corpus
cannot observe an HNSW regression even in principle.

Downloaded on demand rather than redistributed.""",
    ),
    (
        "Cost belongs in the evaluation",
        """$0.0149 per answer with Sonnet 5, $0.0036 with Haiku 4.5 — measured, rather than
estimated from a price list.

Recording it beside quality is what makes the smaller model a decision rather than a
compromise. A quarter of the cost for 0.04 less faithfulness is a trade somebody can
actually make, and neither number means much without the other.""",
    ),
]

UNFILED_NOTES = [
    (
        "Reading list",
        """To go through, in no particular order:

- ColBERTv2, on residual compression for token vectors. The storage cost of late
  interaction is the thing standing between it and being on by default here.
- RAGAS, on reference-free evaluation. Worth knowing whether a judge can be trusted
  without a gold answer to compare against.
- Anything measuring judge-model bias toward its own family. The judge here and the
  model being judged come from the same provider, which is a conflict worth quantifying
  rather than assuming away.""",
    ),
]


def main() -> None:
    try:
        status, _ = call("GET", "/actuator/health")
    except OSError:
        raise SystemExit(f"no API at {API}. Start it with 'make up && make api'.")
    if status != 200:
        raise SystemExit(f"the API at {API} is not healthy ({status}).")

    _, page = call("GET", "/api/notes?limit=200")
    present = {item["title"] for item in page["items"]}

    print("==> topics")
    retrieval = topic("Retrieval")
    evaluation = topic("LLM evaluation")
    print(f"    Retrieval       {retrieval}")
    print(f"    LLM evaluation  {evaluation}")

    print("==> notes on retrieval")
    for title, content in RETRIEVAL_NOTES:
        note(retrieval, title, content, present)

    print("==> notes on evaluation")
    for title, content in EVALUATION_NOTES:
        note(evaluation, title, content, present)

    print("==> unfiled")
    for title, content in UNFILED_NOTES:
        note(None, title, content, present)

    _, page = call("GET", "/api/notes?limit=200")
    _, topics = call("GET", "/api/topics")
    print()
    print(f"{page['total']} documents in the library")
    for held in topics:
        print(f"  {held['name']}: {held['documentCount']}")
    print()
    print("Indexing runs off the write path, so give it a few seconds before searching.")


if __name__ == "__main__":
    sys.exit(main())
