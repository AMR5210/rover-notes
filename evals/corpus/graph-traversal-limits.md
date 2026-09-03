# Graph traversal limits

Expansion from the retrieved seed set is bounded, because unbounded traversal is the
workload a dedicated graph engine exists to serve.

Traversal is capped at 2 hops and runs as a single recursive CTE with a visited-set
guard, so a cycle terminates instead of looping. Fan-out is capped at 25 neighbours per
node, ordered by how many chunks support each edge, which stops a heavily connected
entity from flooding the result.

Expanded chunks join the candidate pool below the fused list rather than merging into it.
They are appended after position 40, so expansion can add recall without displacing
anything the reranker was already going to score.

The traversal is budgeted at 20 ms and is skipped entirely when the earlier stages have
already spent their share of the latency target. Community detection and shortest-path
queries over the whole graph are the point at which a graph engine becomes the right
tool, and this design does not reach for them.
