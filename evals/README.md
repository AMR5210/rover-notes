# Evaluation harness

Measures retrieval and generation quality so that a change to the pipeline can be assessed
against evidence. Results and their confidence intervals are in
[docs/RESULTS.md](../docs/RESULTS.md); this file covers how the harness is laid out and how
to run it.

The harness was built before the advanced retrieval it measures. Every improvement since is
a recorded delta against a baseline, and a change that does not clear its interval does not
ship.

---

## The corpus is a labelled fixture set

`evals/corpus/` holds 42 Markdown files, and the golden sets label answers against them by
document slug *and an exact phrase*. `q002` pins `'ranks rather than scores'` in
`corpus/hybrid-retrieval.md`; seventeen labels are anchored this precisely.

Phrase-level labels are what make a score specific. A label naming only a document would
count a passage as correct for containing the right filename; naming the sentence means the
run is measuring whether retrieval found the words the question was about.

It also makes the corpus immutable. Rewriting a labelled sentence leaves the label
unmatchable and drops that query to zero, lowering nDCG@10 with nothing in the diff to
explain it, so these files are edited only alongside the labels that point into them.

The documents describe this project's design because retrieval quality depends on prose
that reads like the real thing: technical writing, concrete numbers, and vocabulary shared
between related files. They are written to be retrieved rather than to be read as
reference, so `docs/` and the source remain the authorities on how the system behaves.

### Three properties the corpus needs

- **It must exceed the retrieval depth.** At 8 documents against a limit of 20, every query
  returned everything, recall@k was 1.000 by construction, and 23 of 27 queries scored a
  perfect nDCG@10. There was no headroom to measure an improvement in. It is now 42
  documents.
- **Documents must compete.** Several are deliberately adjacent: `outbox-pattern` and
  `postgres-job-queue` both cover queue durability, `semantic-chunking` and
  `chunking-strategy` both cover splitting. A query only discriminates when a plausible
  wrong answer exists, and hard negatives are what create one.
- **Some documents must be long enough to split.** The first 32 ran 683 to 1,485
  characters, so nothing reached the 1,600-character chunking window and every document was
  exactly one chunk. Ten longer documents were added, which lets a citation address a span
  rather than a whole file.

---

## Layout

```
evals/
  corpus/                       42 fixture documents, described above
  golden/                       128 semantic queries: a question, answered by a passage
  golden-known-item/            queries where the reader knows which document they want
  golden-known-item-heldout/    every corpus-unique identifier the slice above did not take
  golden-multihop/              questions needing two documents
  baseline*.json                committed scores each suite is gated against
  history.md                    the full experiment log
```

### The suites, and why they are separate

**`golden/`** is the main set. The query is a question and the answer is a passage.

**`golden-known-item/`** covers the case where the reader already knows which document they
want and is trying to get back to it, by an identifier they remember verbatim, by title, or
by an approximate spelling. This is a different retrieval task, and it is scored against
its own baseline. Merged into one averaged number, a gain on one task would hide a
regression in the other.

`seed.jsonl` here is **generated** from `evals/corpus/` by
`ml_service.evals.build_known_item` and should not be edited by hand. CI regenerates it and
fails if it differs, which catches a corpus change that silently invalidated the slice.

**`golden-known-item-heldout/`** holds every corpus-unique identifier the slice above did not
take. This exists because the first routing measurement was suspect: it scored +0.0646 on
identifiers the generator had chosen, which could have been a gain that held only for tokens
like those. Measured on identifiers it had never seen, the effect was larger.

**`golden-multihop/`** holds questions whose answer requires two documents, used to measure
whether the agent loop reaches what a single retrieval pass cannot.

### Labels

Relevance is expressed as a document slug plus an optional phrase:

```json
{"id": "q002",
 "query": "Why not just normalise the scores before merging them?",
 "relevant": [{"doc": "hybrid-retrieval", "contains": "ranks rather than scores"}]}
```

At `passage` relevance a hit must contain the phrase, so a chunk from the right document
that does not answer the query scores nothing. At `document` relevance the phrase is
ignored. Source documents are hard-wrapped, so a phrase spanning a line break needs care
when it is written.

---

## Two collections

**The 42-document corpus** is written for this project, so labels are exact and the queries
are the kinds of question the system is built for. Recall@20 on it is 1.0000, which makes it
useful for detecting regressions and unable to separate two good rankers.

**SciFact**, from BEIR: 5,183 abstracts and 300 claims written and labelled by other people.
This is the collection that carries weight. It is downloaded on demand and not
redistributed here:

```bash
make eval-scifact-build      # download and build into evals/corpus-scifact/
make eval-scifact            # seed 5,183 abstracts, then score 300 claims
```

Seeding embeds roughly 7,263 chunks and takes about an hour on four CPUs, which is why this
suite runs weekly on a schedule and not on every change.

---

## Running

```bash
make eval                    # score the 42-document corpus against its baseline
make eval-seed               # reset the database and ingest the corpus first
make eval-baseline           # record the current run as the committed baseline
make eval-gate               # compare against the baseline and fail on a regression

make eval-known-item         # the known-item slice
make eval-known-item-heldout # the held-out identifiers
make eval-multihop           # two-document questions
make eval-generation         # answer quality, needs a model and spends credit
```

All of these need `make up` and `make api` running first.

Runs are written to `evals/runs/` with their configuration in the filename. Two runs can be
compared directly:

```bash
cd ml-service && uv run python -m ml_service.evals.compare \
    ../evals/runs/<baseline>.json ../evals/runs/<current>.json
```

---

## Metrics

**nDCG@10** is the primary metric. Rewards putting relevant passages near the top, discounted
by position.

**recall@5 and recall@20** measure whether the relevant passage is present at all within the cut.

**MRR** is the reciprocal rank of the first relevant hit.

**Faithfulness** (generation) measures whether each sentence follows from the retrieved passages.

**Citation precision** (generation) measures whether each citation supports the sentence it is
attached to.

Generation metrics are scored by an LLM judge, which costs money to run. `make
eval-generation-smoke` scores a handful of questions first so a configuration error is
found for cents.

---

## The gate

CI compares per-query, not in aggregate: a randomisation test over per-query differences,
with a bootstrap confidence interval. A change passes when the interval excludes zero in its
favour.

Comparing means alone would pass a change that lifts the average through a handful of
queries while making the rest worse, and would fail a change whose gain is real but smaller
than run-to-run variation. Both have happened during development, which is why the gate
works this way.

The seed is fixed, so a gate decision is reproducible from the committed run.
