-- The lexical channel was indexing the chunk's own text while the dense channel embedded
-- the contextualized version, so contextual retrieval only ever reached half the pipeline.
--
-- Anthropic's description of the technique is explicit that the prefix goes in "before
-- embedding it and before creating the BM25 index", and its reported numbers separate the
-- two: a 35% reduction in retrieval failures from embeddings alone against 49% once the
-- lexical index carries the context too. This project measured -0.0148 nDCG@10 for
-- "contextual retrieval" with the lexical half absent, so that figure describes something
-- narrower than the technique it was named after.
--
-- `content` stays the raw span. It has to: char_start and char_end index into it, and a
-- citation resolves through those, so prefixing it would move every offset and point every
-- citation at the wrong text. The contextualized string goes in the column the baseline
-- migration already declared for it and never populated.
--
-- coalesce, so a chunk written with context off — where the column is null — indexes
-- exactly what it indexed before. The committed baseline is unaffected by this migration.

drop index if exists chunks_tsv_idx;

alter table chunks drop column tsv;

alter table chunks
    add column tsv tsvector
    generated always as (to_tsvector('english', coalesce(contextualized_content, content)))
    stored;

create index chunks_tsv_idx on chunks using gin (tsv);
