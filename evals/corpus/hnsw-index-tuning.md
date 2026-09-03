# HNSW index tuning

The chunk embedding column is indexed with HNSW rather than IVFFlat because content
arrives continuously and HNSW supports incremental insert without a clustering step over
representative data.

Build parameters are m = 16 and ef_construction = 64. Raising m increases graph degree
and memory: at 300,000 vectors the graph links add roughly 40 MB on top of the stored
vectors. Index builds run with maintenance_work_mem raised to 2 GB so the graph is
assembled in memory rather than spilled to disk.

Search quality is governed by ef_search, which defaults to 40. It must be at least the
requested limit, so a channel fetching 100 candidates should raise it per session rather
than rely on the default. Recall is validated against an exact brute-force scan, which
is the ground truth for any tuning change.

Rebuilds use REINDEX INDEX CONCURRENTLY so retrieval stays available while the new graph
is built. A rebuild is required after an embedding model change and after a parameter
change, never after ordinary inserts.
