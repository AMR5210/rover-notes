# Architecture

How Rover Notes is built, the decisions behind it, and the numbers each decision rests on.
Where a choice has a threshold that would reverse it, the threshold is stated.

---

## Shape of the system

```
Next.js                 frontend and auth session handling, no business logic
        │ REST + SSE
        ▼
Spring Boot 4.1         modular monolith (Spring Modulith 2.1, Java 21, virtual threads)
  ├─ notes              documents, topics, CRUD, outbox
  ├─ ingestion          parse → chunk → embed → index
  ├─ retrieval          dense + lexical + RRF fusion + rerank
  ├─ agent              retrieve-then-generate, blocking and streamed
  ├─ identity           OAuth 2.1 with PKCE, Argon2id
  ├─ usage              per-request cost attribution and spend caps
  └─ mcp                search, get_document, list_documents
        │
        ├──────────────► ml-service (Python)  embeddings, reranking, PDF and web parsing
        │
        ▼
PostgreSQL 17           vectors, full-text, queue, usage accounting

S3-compatible storage   original uploaded files (MinIO locally)
```

Four deployables: the API, the Python model and parsing service, the web frontend, and one
database.

---

## A modular monolith, with enforced boundaries

The API is a single deployable divided into modules that communicate through published
application events and explicitly exported types. `ModularityTests` verifies the boundaries
and fails the build when one module reaches into another's internals.

This keeps a document and its indexing job in one transaction. Spring Modulith persists
application events in the same transaction as the write, so the publication *is* the
transactional outbox: a document and the job that will index it commit together or not at
all. Splitting these into services introduces a dual-write problem that has to be solved
with a distributed transaction or an outbox anyway.

The boundary test is what keeps this durable: a boundary maintained by convention drifts as
the code changes, while one that fails the build does not.

The Python sidecar exists because its work differs in kind. Model serving and document
parsing are CPU-bound and bursty, and the libraries that do them well are Python ones.

**When this would change:** independent scaling requirements between modules, or more than
one team owning parts of the system.

---

## One Postgres for vectors, full-text, and queue

A single PostgreSQL 17 instance covers five concerns:

| Concern | Mechanism |
|---|---|
| Dense retrieval | `pgvector` with an HNSW index |
| Lexical retrieval | `tsvector` + GIN |
| Identifier and filename lookup | `pg_trgm` + GIN |
| Cost and usage time series | A table with a BRIN index on `created_at` |
| Job queue | Outbox table claimed with `SKIP LOCKED` |

Each could be a dedicated system. None is, because at this size the operational cost of a
second datastore exceeds what it buys, and because keeping vectors beside the rows they
rank removes an entire class of consistency bug.

**The vector index by corpus size:**

| Corpus | Index choice |
|---|---|
| < 10K vectors | Brute-force exact scan, 100% recall and within the latency budget |
| **10K – 10M** | **pgvector HNSW on one Postgres** ← current design point |
| 10M – 100M | int8 or binary quantization for the scan, rerank on full vectors |
| 100M+ | Sharding, DiskANN, IVF-PQ, or a dedicated vector store |

**Design point:** ~50,000 documents, ~300,000 chunks, under 10 sustained read QPS, single
tenant.

---

## Topics, which the ranker does not see

A document can be filed under a topic: a name, one per document, chosen by the person who
owns it. The library page filters by one; search, answers and the MCP tools do not.

This is a deliberate split. Retrieval finds a passage wherever it sits, and narrowing the
candidate set by a label the reader guessed would remove passages the ranker would have
found, and the failure it causes is silent, because a missing answer looks the same as no
answer. What a topic solves is the question that comes before a query: which subject a
document belongs to, when several unrelated ones are being read at once, which is not a
question a ranker answers.

**One topic per document rather than a tag set.** The general model is many-to-many, and
the requirement is where a document sits, which is one place. A join table would also turn
"documents with no topic" into a not-exists subquery, and that is the busiest filter here:
every document written before the feature existed is in it.

**Scope is a foreign key, not a check in the service.** `documents.topic_id` references
`topics (id, owner_id)` rather than `topics (id)`, so a document cannot carry another
account's topic. The database refuses it, in the same way V3 stopped rows being written
for owners who do not exist. Deleting a topic clears the label and keeps the documents:
`on delete set null (topic_id)`, whose column list is what lets a composite key have that
action at all, since an unqualified `set null` would also null the `not null` owner.

**A move re-embeds nothing.** Chunks carry text, offsets and the owner, so changing a
document's topic publishes no `DocumentChanged` and does not touch `updated_at`. The write
is durable; the indexing work behind it is skipped because there is none to do.

---

## Postgres as the job queue

Indexing runs off the write path through a transactional outbox table, claimed with
`SELECT ... FOR UPDATE SKIP LOCKED` and woken by `LISTEN/NOTIFY`.

A broker would add a second piece of infrastructure to operate, monitor and secure, and
would reintroduce the dual-write problem the outbox exists to remove. `SKIP LOCKED` gives
correct concurrent claiming, and the table is empty in the steady state.

**Adopt Kafka when:** multiple independent consumer groups need the same event stream, or
throughput exceeds ~5K events/sec.

---

## Hybrid retrieval

Dense and lexical retrieval run in parallel and their rankings are fused with Reciprocal
Rank Fusion at k = 60:

```
score(d) = Σ  1 / (60 + rank_i(d))
          i∈{dense, lexical}
```

RRF combines ranks, not scores, which avoids having to calibrate two scoring functions with
different scales and distributions against each other.

**k = 60** is the value from Cormack et al. (2009). Swept over 10, 30, 60 and 100 on this
corpus: k = 30, 60 and 100 produce identical per-query scores, and k = 10 moves three
queries by an amount the set cannot resolve.

**Why both channels stay.** The two fail on different queries. Embeddings compress a rare
token into general topical meaning, so the dense channel averages 0.6965 nDCG@10 on
identifier queries against 0.9710 for lexical. Word matching has the opposite weakness and
misses paraphrase entirely.

Fusion does not beat both channels in every measurement, and the file that records this is
[RESULTS.md](RESULTS.md). Against dense retrieval on the 42-document corpus the interval
spans zero; on SciFact fusion scores slightly below dense alone. What the measurements do
support is that each channel covers queries the other loses, and that the fallback to
lexical-only is a weaker answer and not a broken one: at this corpus size it keeps 96% of
the quality at an eighth of the latency.

**Lexical ranking uses `ts_rank`, not BM25.** PostgreSQL computes no IDF: its ranking
functions are `IMMUTABLE` and see no corpus statistics, so `ts_rank` supplies
term-frequency saturation and nothing else. It replaced `ts_rank_cd`, whose linear growth
in occurrences ranked a chunk matching one query term ten times above a chunk matching all
three terms once each, worth **+0.0399 nDCG@10** (CI +0.0129 to +0.0700). A true BM25
implementation is a candidate for a later measured comparison.

---

## Reranking by late interaction

Reranking scores the top 40 fused candidates. Two implementations are available and the
strategy is configurable.

**Late interaction** (default when reranking is requested) encodes query and passage into
one vector per token and scores a pair by MaxSim: for each query token, its best match
among the passage's tokens, summed. A single-vector bi-encoder carries a whole passage in
one point and loses the rare term a question turns on; late interaction keeps a vector for
that term and matches it directly.

On SciFact this is worth **+0.0510 nDCG@10** (95% CI +0.0291 to +0.0734, 85 queries better
and 27 worse of 300). The model is `mixedbread-ai/mxbai-edge-colbert-v0-32m`, chosen over
the stronger 149M `GTE-ModernColBERT-v1` because it scores 40 passages on the same CPU
serving the request. On hardware with a GPU the larger model is the better choice.

**A cross-encoder** remains selectable. It scores query and passage jointly in one forward
pass per pair, and costs about a third as much. On the 42-document corpus the two are
indistinguishable, so the cross-encoder is the better choice on small collections.

**Reranking is off by default.** Late interaction costs 7.7 s at p95 on SciFact and the
cross-encoder 2.2 s, both far outside the 150 ms retrieval budget. It is requested per
query.

**What would change this:** storing token vectors at ingest instead of encoding candidates
per query, which is how published systems serve late interaction and would remove most of
the latency. It multiplies the vector store by the token count of the corpus, making it a
storage decision.

---

## Hosted generation, self-hosted embeddings

- **Synthesis and agent model:** hosted APIs, Anthropic by default
- **Embeddings and reranking:** self-hosted, small, CPU-only, via Hugging Face Text
  Embeddings Inference

The cost profiles point in opposite directions. Embedding runs on every chunk at ingest and
every query at retrieval, which is the profile where a small self-hosted model on CPU costs
less than per-token pricing. Generation runs once per question against a model too large to
serve economically at this volume. The cheapest realistic GPU for a 7–14B model costs more
per month than the measured API spend by a wide margin.

Measured cost per answer: **$0.0149** with Sonnet 5, **$0.0036** with Haiku 4.5.

---

## Identity issued by this service

Tokens are issued by Spring Authorization Server 7.1 and the user store lives in this
project's PostgreSQL. The split is deliberate:

- **The protocol is not written here.** Authorization code flow with PKCE, token issuance,
  refresh, JWKS publication and discovery metadata come from Spring Authorization Server.
- **The user lifecycle is written here.** Registration, password verification, email
  confirmation, reset and lockout are application concerns, backed by the `users`,
  `user_authorities` and `user_tokens` tables.

Passwords use Argon2id at the OWASP baseline (19 MiB, two passes, one lane) behind a
`DelegatingPasswordEncoder`, so each stored hash carries its own parameters and can be
upgraded without a reset.

This supersedes an earlier decision to use a managed identity provider. Self-hosting the
identity server keeps the whole system runnable from one compose file with no external
account, which matters for a system whose premise is that documents stay on infrastructure
you control.

---

## Platform baseline

Spring Boot 4.1 on Java 21, with Spring Modulith 2.1 and Testcontainers 2.0.5.

Java 21 gives virtual threads, which suit this workload: request handling is dominated by
waiting on the embedding server, the database and the model API. Testcontainers runs
integration tests against real PostgreSQL with pgvector, so the schema, the vector columns
and the retrieval SQL are exercised as deployed. The suite shares one container across the
run, which brought it from 982 seconds to 106.

---

## Deployment target

Azure, using Container Apps, PostgreSQL Flexible Server, Blob Storage and a container
registry. The template is in [`infra/`](../infra) and deploys with one script.

```
Container Apps environment
  ├─ web    public ingress, TLS terminated by Container Apps
  ├─ api    internal ingress, reached through the frontend
  └─ ml     internal ingress: embeddings, reranking, parsing
PostgreSQL Flexible Server   Standard_B1ms, PostgreSQL 17, VECTOR and PG_TRGM enabled
Storage account              one private container for uploaded originals
Container registry           Basic, no admin user
```

**Credentials are identities, not secrets.** The API reads and writes blobs through a
system-assigned managed identity holding Storage Blob Data Contributor on that account
alone, so no storage key is issued, configured or rotated. Each container app pulls its
image with its own identity holding AcrPull, so the registry has no admin user. Three
values are supplied at deploy time and held as container app secrets: the database
password, the model API key, and the key-encryption key protecting the token signing keys
at rest.

**The issuer is the frontend's address, not the API's.** Only the frontend has external
ingress, and it proxies `/oauth2`, `/login` and `/.well-known` through to the API, so the
address a browser reaches is the frontend's. Tokens carry the issuer and clients validate
against it, which rules out the API's internal name: a token issued under it would be
refused by whatever received it. The template builds that origin from the
container app environment's domain rather than reading it off the frontend, because the
API is declared first and referencing it the other way would be a cycle. The same value
supplies the interface URL in account email and the client's registered redirect URI.

**Object storage needed a second implementation.** Azure Blob does not speak the S3 API,
so `FileStore` is an interface with an S3 implementation covering MinIO locally and an
Azure Blob implementation for the deployment. Both address a document by the same key, so
a corpus written under one is readable under the other.

**Cost:** roughly $3–4 a day left running, dominated by the Container Apps environment and
the burstable database. Everything sits in one resource group, so `infra/destroy.sh`
removes it completely, which matters because a stopped container app still bills for its
environment and the database bills whether or not anything connects.

**The database is publicly networked with a firewall rule for Azure services.** A private
endpoint is the appropriate choice for a deployment holding production data; it also
requires a virtual network, a private DNS zone and a NAT gateway, which together cost more
than the rest of the deployment.

AWS was the earlier target and was costed at about $154 a month for the equivalent shape.
The two are close enough that neither has a price advantage. Azure was chosen for its
managed identity support, which removes the storage credential entirely.

**Resident memory, measured locally:** API (JVM) 993 MiB, embeddings 301 MiB, reranker
198 MiB, Postgres 102 MiB, ml-service 38 MiB idle.

---

## Deferred components and their triggers

| Component | Adopt when |
|---|---|
| Kafka | Multiple independent consumer groups, or > 5K events/sec |
| Redis | A measured cache-miss latency problem |
| Kubernetes / ArgoCD | Multi-service scaling divergence, or a team |
| Canary deploys | Enough traffic for a 5% slice to be statistically meaningful |
| Vault | More than a handful of secrets, or a rotation requirement |
| Dedicated vector database | > 10M vectors |
| Exact-match retrieval stage | A measured recall gap; currently recall@20 is 1.0000 |

Each is a component this design does not include. The trigger column is what would change
that, so the decision can be revisited against evidence.
