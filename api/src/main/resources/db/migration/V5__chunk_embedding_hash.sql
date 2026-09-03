-- Re-indexing a document re-embedded every chunk in it, whatever had changed.
--
-- `content_hash` was already here for this, but it is the wrong key to reuse an
-- embedding on. What gets embedded is not always the chunk's own text: with
-- `rover.ingestion.chunk-context` on, the vector is computed from the chunk prefixed
-- by its document title and section heading. Two chunks with identical text under
-- different headings embed differently, and a document renamed without its body being
-- touched needs new vectors while every `content_hash` stays as it was.
--
-- So the reuse key is a hash of the exact string handed to the embedding model.
-- `content_hash` keeps its own job: it is the tie-break that makes retrieval ordering
-- deterministic, and is documented as the hash of `content`.
alter table chunks add column embedding_hash text;

-- Existing rows are left null on purpose rather than backfilled from `content_hash`.
-- Null matches nothing, so the first re-index of a document written before this
-- migration embeds it once more and records the key; every re-index after that reuses.
-- A backfill would have to assume chunk context was off when the row was written, and
-- that assumption is wrong exactly when it is expensive: a vector reused against the
-- wrong text is a retrieval fault with nothing to show for it in the schema.
create index chunks_embedding_hash_idx on chunks (document_id, embedding_hash);

-- Chunks keep their rows across a re-index now, so ordinals are reassigned in place
-- rather than deleted and reinserted. Two rows swapping positions collide on this
-- constraint partway through that update while ending in a valid state, which is what
-- deferring the check to commit allows. The constraint itself is unchanged.
alter table chunks drop constraint chunks_ordinal_unique;
alter table chunks add constraint chunks_ordinal_unique
    unique (document_id, ordinal) deferrable initially deferred;
