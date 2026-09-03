# Performance tuning guide

Where the time goes, and which knobs actually move it.

## The budget

The stated target is a 95th-percentile of 150 milliseconds for everything before the
language model call. That number is not arbitrary: it is roughly the point at which added
latency stops being noticed against the several seconds the generated answer itself takes,
so spending more buys nothing a reader can feel.

Measured against the evaluation corpus on shared cores, the pipeline sits around 50
milliseconds with the cross-encoder off. The cross-encoder costs roughly 25 milliseconds
per candidate pair on the same hardware, which is what puts it outside the budget rather
than any property of the model.

## Sizing the connection pool

A pool larger than the database can serve does not increase throughput; it converts a
queue in the application into a queue in the database, where it is harder to see. The
useful bound is the number of cores the database has multiplied by a small factor for I/O
overlap, and for a single modest instance that lands near sixteen.

Symptoms of an undersized pool and an overloaded database look identical from the
application: requests wait. The distinguishing measurement is whether the database's own
active connection count is at the pool ceiling or below it.

## Graph index parameters

Two settings govern a vector graph index. One controls how many neighbours each node keeps
and is fixed at build time; raising it improves recall and costs build time and memory
permanently. The other controls how many candidates a search keeps in flight and is set
per query; raising it improves recall and costs latency on that query alone.

The second is the one worth tuning, because it is reversible. The first is a rebuild.

Neither matters below the size at which the planner chooses the index at all. On a table
of a few thousand rows an exact scan is cheaper than a graph traversal, the planner knows
it, and tuning traversal parameters changes nothing, a result that is easy to mistake for
"the setting does not help" when the truth is "the setting was never used".

## Batch sizes

Every call to a model server has a fixed overhead, so batching helps until the batch
exceeds what the server accepts. Beyond that the request is rejected, and a client with a
fallback path turns the rejection into a quietly worse answer rather than an error. Set
the client's batch below the server's limit, not equal to it, and treat the two as
independent settings that happen to be related.

## Caching

Nothing is cached today. The obvious candidate is the query embedding, since a repeated
query embeds to the same vector, and the obvious objection is that in a personal knowledge
base the same query is rarely asked twice in the window a cache would cover. The
measurement that would settle it is the repeat rate of queries within an hour, which is
not yet recorded.

## How to profile

Measure end to end before measuring anything else. The pipeline is a handful of stages and
the slow one is usually obvious from stage timings alone; a profiler comes out only when
the total and the sum of the parts disagree, which points at connection acquisition or
serialisation rather than at any stage.
