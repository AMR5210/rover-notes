# pgvector distance operators

pgvector 0.8.6 exposes several distance operators, and an index accelerates only the one
its operator class was built for.

Retrieval uses cosine distance, written `<=>`, against an index created with
`vector_cosine_ops`. Euclidean distance `<->` and negative inner product `<#>` have
their own operator classes; a query written with one of them against a cosine index
falls back to a sequential scan. Similarity is reported to callers as `1 - (a <=> b)`,
so a score of 1.0 is an exact match and ordering is ascending by distance.

Stored embeddings are L2-normalised at write time, which makes cosine and inner product
rank identically. Cosine is kept because it is the metric the embedding model was
trained under and because the derived score is bounded and easy to threshold.

The dimension is fixed in the column type at schema level, so a model producing a
different width needs a new column rather than a cast. `halfvec` halves storage at some
cost in precision and is deferred until the corpus passes 10M vectors.
