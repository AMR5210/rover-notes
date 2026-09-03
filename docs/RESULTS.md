# Results

Retrieval and generation quality, measured. Every figure here comes from a command in this
repository, and every comparison reports a confidence interval alongside the mean.

A change is adopted when its 95% interval excludes zero. Several changes below did not
clear that bar and were not adopted; they are recorded with the ones that were, because a
technique that does not help on this workload is as useful to know as one that does.

**Reproducing these:** `make eval` scores the 42-document corpus, `make eval-scifact`
scores SciFact, `make eval-generation` scores answer quality. See
[evals/README.md](../evals/README.md).

---

## Headline figures

| What | Result |
|---|---|
| Retrieval quality, SciFact (5,183 abstracts, 300 claims) | **nDCG@10 0.7324** with late-interaction reranking |
| Retrieval quality, SciFact, committed defaults | **nDCG@10 0.6804**, recall@20 0.8893 |
| Retrieval quality, 42-document corpus (128 queries) | **nDCG@10 0.8254**, recall@5 0.9297 |
| Answer faithfulness | **0.9688** |
| Citation precision | **0.9723** |
| Abstention on unanswerable questions | **8 of 8** |
| Retrieval p95, serial | **32 ms** before the model call |
| Retrieval p95, 10 concurrent | **94 ms** |
| Cost per answer | **$0.0149** Sonnet 5, **$0.0036** Haiku 4.5 |
| Test suites | **447 JVM**, 223 Python, 56 browser |

---

## Two collections

Retrieval is scored against two corpora, which measure different things.

**A 42-document corpus** of hand-written technical documents with 128 labelled queries.
Written for this project, so the labels are exact and the queries are the kinds of question
the system is built for. Recall@20 on this collection is 1.0000, which makes it useful for
detecting regressions and unable to separate two good rankers.

**SciFact**, from the BEIR benchmark: 5,183 scientific abstracts and 300 test claims,
written and labelled independently of this project. This is the collection that carries
weight, and the one with enough headroom to separate two rankings. It is downloaded on
demand by `make eval-scifact-build` and is not redistributed here.

---

## Retrieval, by change

| Corpus | Change | nDCG@10 | recall@5 | p95 (ms) | Adopted |
|---|---|---|---|---|---|
| 32 docs | Dense only | 0.8733 | 0.9535 | 33 | reference |
| 32 docs | Lexical only | 0.8518 | 0.9651 | 6 | reference |
| 32 docs | Hybrid, RRF k=60, `ts_rank_cd` | 0.8887 | 0.9767 | 28 | superseded |
| **32 docs** | **Hybrid + `ts_rank`** | **0.9287** | **0.9884** | 39 | **baseline** |
| 32 docs | Query router, known-item slice | 0.9381 | 0.9888 | 37 | **on by default** |
| 32 docs | Router fallback, held-out identifiers | 0.9728 | 1.0000 | 8 | **on by default** |
| **42 docs** | **Corpus extended with 10 long documents** | **0.8254** | **0.9297** | 32 | **baseline** |
| 42 docs | Semantic chunking, granularity matched | 0.8285 | 0.9375 | 39 | not adopted |
| 42 docs | Heading context on chunk embeddings | 0.8107 | 0.9375 | 35 | not adopted |
| **SciFact** | **Hybrid, committed defaults** | **0.6804** | **0.7416** | 85 | **baseline** |
| SciFact | Dense only | 0.6873 | 0.7518 | 38 | reference |
| SciFact | Lexical only | 0.5798 | 0.6372 | 45 | reference |
| SciFact | `ef_search` 40 → 100 | 0.6843 | 0.7416 | 79 | not adopted |
| SciFact | Cross-encoder rerank | 0.6814 | 0.7626 | 2224 | reference |
| **SciFact** | **Late-interaction rerank** | **0.7324** | **0.8057** | 7717 | **default when reranking** |

---

## What the measurements support

### Saturating lexical ranking

Replacing `ts_rank_cd` with `ts_rank` is worth **+0.0399 nDCG@10** (CI +0.0129 to +0.0700,
12 queries better and 2 worse).

`ts_rank_cd` grows linearly in the number of occurrences, which ranked a chunk matching one
query term ten times above a chunk matching all three terms once each. `ts_rank` saturates.
Neither computes IDF. PostgreSQL's ranking functions are `IMMUTABLE` and see no corpus
statistics, which is why the lexical channel scores 0.5798 on SciFact against BEIR's
published 0.665 for BM25.

### Routing identifier queries to the lexical channel

Worth **+0.0483 nDCG@10** on the known-item slice (CI +0.0163 to +0.0832) and **+0.1573**
on held-out identifiers (CI +0.1069 to +0.2105, 30 better and 2 worse of 93). The cost on
the semantic set is +0.0016, where the rule fires on 2 of 128 queries.

Embeddings compress a rare token into general topical meaning: the dense channel averages
0.6965 nDCG@10 on identifiers against 0.9710 for lexical.

The held-out suite exists because the first measurement was suspect. Routing scored +0.0646
on identifiers the generator had chosen, which could have been a gain that held only for
tokens like those. A second suite was built from every corpus-unique identifier the first
did not take. Measured there, the effect was larger, not smaller.

That second run also exposed two defects: the lexical channel double-stemmed every query,
and routing could return nothing where fusion returned something. After both fixes, held-out
identifiers moved **0.8840 → 0.9728** (+0.0887, CI +0.0480 to +0.1321, 17 better and 1
worse of 80).

### Late-interaction reranking

On SciFact, **+0.0510 nDCG@10** (CI +0.0291 to +0.0734, 85 better and 27 worse of 300),
with recall@5 +0.0432 and recall@20 +0.0223. All three intervals exclude zero.

The movement in recall@20 has a specific cause. A reranker only reorders the candidate list
it is given, and these metrics score at a depth inside that list, so a passage fusion ranked
25th and the reranker ranked 15th enters recall@20 with no change to retrieval. Eight
queries gained one and none lost one.

The same comparison on the 42-document corpus is **+0.0098 with the interval spanning zero**
(CI −0.0230 to +0.0426, 90 of 128 queries unchanged). That collection has recall@20 of
1.0000, so both rerankers reorder a list already containing every relevant passage. A set
that cannot separate two systems is not evidence that they are equivalent, which is why the
default follows the larger collection.

---

## What did not work

### Semantic chunking

Two rounds, neither adopted.

The first split on embedding similarity between sentences and measured **−0.0590 nDCG@10**
(CI −0.0978 to −0.0240, p=0.001), but on a corpus whose documents were too short to split
meaningfully, so the comparison was between a real chunker and one with nothing to do.

The second matched granularity properly on the extended corpus: **+0.0030 nDCG@10**
(CI −0.0257 to +0.0339). Indistinguishable from fixed-size windows, at a higher ingest cost.
Fixed windows remain the default: with quality indistinguishable, ingest cost decides.

### Contextual retrieval

Prefixing each chunk with a short description of its place in the document, applied to both
retrieval channels: **−0.0219 nDCG@10** (CI −0.0498 to +0.0047).

Measured per channel, the dense channel gained **+0.0164** and the lexical channel lost
0.0059. The published technique assumes a lexical ranker with IDF, which discounts a term
appearing in every document. `ts_rank` computes none, so a prefix repeated across every
chunk of a document adds matching mass without adding discrimination. Fusion then loses
more than either channel alone, because giving both channels the same prefixed text makes
their rankings correlate and leaves less independent signal to fuse.

The technique works on the dense channel here. What it does not survive is being applied to
both channels of a hybrid system whose lexical ranker has no corpus statistics.

### HNSW `ef_search` at 100

**0.6843 against 0.6804** on SciFact, at nearly double the p95. Within noise for a
measurable cost.

### Haiku 4.5 for generation

Faithfulness 0.9289 and citation precision 0.9438, against 0.9688 and 0.9723 for Sonnet 5,
at roughly a quarter of the cost per answer. Recorded so the trade-off is available;
Sonnet 5 remains the default.

---

## Generation quality

Scored by an LLM judge over the 42-document corpus.

| Metric | Sonnet 5 | Haiku 4.5 |
|---|---|---|
| Faithfulness | **0.9688** | 0.9289 |
| Citation precision | **0.9723** | 0.9438 |
| Unanswerable questions declined | **8 of 8** | 8 of 8 |
| Cost per answer | $0.0149 | $0.0036 |

Faithfulness measures whether each sentence follows from the retrieved passages. Citation
precision measures whether each citation supports the sentence it is attached to. The
abstention count is the eight questions in the evaluation set the corpus cannot answer;
both models decline all eight.

---

## Latency

Measured with `load/search.js` against the running stack.

| Concurrent users | p95 | Throughput |
|---|---|---|
| 1 | 20 ms | ~80 req/s |
| 5 | 48 ms | ~105 req/s |
| 10 | 94 ms | ~106 req/s |
| 20 | 183–273 ms | ~106 req/s |

Zero failed requests at every level, so this is latency and not errors. The 150 ms target
holds to about ten concurrent users.

The embedding call accounts for most of that time. Timed in process, per request:

| Stage | 1 user | 20 users |
|---|---|---|
| Embedding (HTTP to TEI) | 15.0 ms | 159.2 ms |
| Retrieval SQL (fusion, both channels) | 3.6 ms | 8.5 ms |

At twenty users the embedding call is 159 ms of a 169 ms request. It is saturation and not
slowness: the same call is 13 ms at one user. Lexical-only requests under the same load
stay at 30.5 ms p95 at 1,308 req/s, which confirms the dense channel as the constraint.

Four vCPUs carry the load generator, the JVM, PostgreSQL and both model servers, so some of
this queueing belongs to the machine.

---

## Test coverage

| Suite | Count | Against |
|---|---|---|
| JVM | 447 | Real PostgreSQL with pgvector, via Testcontainers |
| Python | 223 | Under ruff, ruff format and strict mypy |
| Browser | 56 | A real browser driving the live API |

The JVM suite shares one database container across the run, which brought it from 982
seconds to 106.

Browser tests exist for one contract in particular: a citation carries `charStart` and
`charEnd`, and the interface turns that pair into a highlighted passage. A change to the
response shape or to chunking would break the highlight while every other suite stayed
green.

---

## How the gate works

CI scores retrieval against the committed baseline and compares per-query, not in
aggregate. The test is a randomisation test over per-query differences with a bootstrap
confidence interval; a change passes when the interval excludes zero in its favour.

Comparing means alone would pass changes that move the average through a handful of queries
while making the rest worse, and would fail changes whose gain is real but smaller than
run-to-run variation.
