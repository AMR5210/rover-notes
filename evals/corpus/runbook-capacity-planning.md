# Capacity planning

What grows, what it costs, and the point at which each piece stops being adequate.

## What actually grows

Three quantities, and they grow at different rates. Documents grow with use. Pieces grow
with documents and with any change to how documents are split, which means a chunking
change is a capacity event as well as a quality one. Usage rows grow with queries, which
in a personal knowledge base is the fastest-growing table and the least valuable one.

Vectors dominate the size but not the growth rate. At 384 dimensions a piece costs about
1.5 kilobytes of vector and a similar amount of text and search index, so a hundred
thousand pieces is well under a gigabyte in total.

## The single-instance envelope

One database instance serves this design comfortably to somewhere around a million
vectors. The binding constraint is not disk and not query time; it is that a graph index
performs well while it fits in memory and degrades sharply when it does not, so the
practical ceiling is set by instance memory rather than by any property of the query.

Beyond that the first move is a larger instance, which is a restart. The second is
partitioning by owner, which turns one large index into many small ones and suits a
workload where every query is already scoped to a single owner. A dedicated vector service
comes after both and is the point at which a note and its vector stop committing in the
same transaction, which is the property this design exists to keep.

## Query throughput

Retrieval is two statements and, when enabled, one call to a model server. Without the
cross-encoder a request costs tens of milliseconds of database time, so a single instance
serves hundreds of concurrent readers before the pool becomes the constraint. With the
cross-encoder the model server saturates first, and it saturates at single-digit
concurrency on shared cores.

That asymmetry is the real argument for keeping reranking off by default rather than the
latency figure alone: a stage that is six times the budget at one request per second is
unavailable entirely at fifty.

## Ingestion throughput

Bounded by embedding, not by writes. The queue drains at roughly the rate the model server
can embed, and the useful lever is batch size until the server's limit is reached. Past
that the lever is a second model server, since the work is trivially parallel and holds no
state.

## Cost

Infrastructure cost at this scale is dominated by keeping containers running rather than
by anything proportional to use. The variable cost that matters is language model tokens,
which is why every call records its token counts against an owner. A single instance, a
small database, and object storage sit in the tens of dollars a month; a careless
synthesis prompt on a large context can exceed that in a day.

## When to revisit

Three triggers, each with a number. Vectors past a million, which is the index-in-memory
boundary. Median query latency past a hundred milliseconds with no code change, which
usually means the working set stopped fitting. And a queue depth that does not return to
zero overnight, which means ingestion has stopped keeping up with writing.
