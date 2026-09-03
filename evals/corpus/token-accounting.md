# Token accounting

Every model call writes a usage row, so spend is a query rather than an estimate.

The row records the request identifier, the model, input tokens, output tokens,
cache-write tokens, cache-read tokens, and the computed cost in micro-dollars stored as
an integer. Integer micro-dollars avoid the floating-point drift that appears once
millions of rows are summed. The table carries a BRIN index on created_at, which suits an
append-only time series and occupies a fraction of the space a B-tree would.

The request identifier also appears on the trace span, so an unusually expensive answer
can be followed back to the retrieval that assembled its context.

A monthly aggregate is materialised nightly for the in-app usage view, and raw rows are
rolled into daily summaries after 90 days. At current volume the detail table would take
years to become a problem, so the rollup exists mainly to make the retention policy
explicit rather than to reclaim space.
