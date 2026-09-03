# Hybrid retrieval

Retrieval runs two channels in parallel and merges them.

The dense channel embeds the query with the same model used at ingest and compares it
against chunk embeddings using cosine distance, served by an HNSW index. It is strong on
topical similarity: a query about "combining ranked lists" will surface a passage about
fusion even when no word overlaps.

The sparse channel scores the query against a tsvector index. It matches on the literal
token, so an identifier that appears in three chunks out of three hundred thousand is
found exactly rather than approximately. This is what carries known-item lookups, where
dense embeddings are weakest because they compress rare tokens into general topical
meaning.

PostgreSQL's ranking functions are worth being precise about: `ts_rank` and
`ts_rank_cd` are immutable functions of a tsvector and a tsquery, so they use no
corpus statistics and compute no IDF at all. Rarity helps here by making the match
itself selective, not by weighting the score.

The two ranked lists are merged with Reciprocal Rank Fusion. Each document scores the
sum of 1/(60 + rank) across the lists it appears in. Fusion operates on ranks rather
than scores, which avoids having to normalise cosine similarity against unbounded BM25
values. The constant 60 comes from the original 2009 paper, where it was fixed during a pilot
and never varied, rather than shown to be optimal.

Fused candidates then pass through a cross-encoder reranker.
