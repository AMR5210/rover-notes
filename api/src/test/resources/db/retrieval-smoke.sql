\set ON_ERROR_STOP on
\set owner '''22222222-2222-2222-2222-222222222222'''

-- Start from an empty corpus. CI runs schema-smoke.sql against this same database
-- first, and it seeds chunks under the same owner — one of them, "Reciprocal rank
-- fusion merges dense and sparse candidate lists", stems to the same lexemes as the
-- query below and is a legitimate lexical match. The assertions here are about which
-- chunks a channel returns, so the file has to own its fixtures rather than inherit
-- whatever ran before it.
truncate documents cascade;
-- Build a deterministic 384-dim vector where dimension 0 encodes a "topic" signal,
-- so nearest-neighbour ordering is predictable and the assertion is meaningful.
create or replace function mkvec(topic float) returns vector as $$
  select ('[' || topic || ',' ||
          array_to_string(array(select 0.01 from generate_series(2,384)), ',') || ']')::vector;
$$ language sql immutable;

insert into documents (id, owner_id, title, content, content_hash) values
  ('aaaaaaaa-0000-0000-0000-000000000001', :owner, 'Retrieval notes', 'body', 'h1'),
  ('aaaaaaaa-0000-0000-0000-000000000002', :owner, 'Deployment notes', 'body', 'h2');

insert into chunks (document_id, owner_id, ordinal, content, content_hash, char_start, char_end, embedding) values
  ('aaaaaaaa-0000-0000-0000-000000000001', :owner, 0, 'RRF fuses ranked lists',        'c1', 0, 22, mkvec(1.0)),
  ('aaaaaaaa-0000-0000-0000-000000000001', :owner, 1, 'Cross-encoders rerank results', 'c2', 23, 52, mkvec(0.6)),
  ('aaaaaaaa-0000-0000-0000-000000000002', :owner, 0, 'Terraform provisions ECS',      'c3', 0, 25, mkvec(-1.0));

-- Another owner's chunk: must never appear in results below.
insert into chunks (document_id, owner_id, ordinal, content, content_hash, char_start, char_end, embedding)
values ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 2,
        'other owner data', 'c4', 0, 16, mkvec(1.0));

\echo '--- RetrievalService.search() SQL, verbatim ---'
select c.id            as chunk_id,
       c.document_id   as document_id,
       d.title         as title,
       c.content       as content,
       c.char_start    as char_start,
       c.char_end      as char_end,
       round((1 - (c.embedding <=> mkvec(1.0)))::numeric, 4) as score
  from chunks c
  join documents d on d.id = c.document_id
 where c.owner_id = :owner
   and c.embedding is not null
 order by c.embedding <=> mkvec(1.0)
 limit 10;

\echo '--- owner isolation: rows visible to owner 2222 ---'
select count(*) as own_rows from chunks where owner_id = :owner;

\echo '--- RetrievalService lexical channel: ts_rank over the generated tsv ---'
select c.content, round(ts_rank(c.tsv, q.query)::numeric, 6) as score
  from chunks c
  join documents d on d.id = c.document_id,
       to_tsquery('english', nullif(array_to_string(
           tsvector_to_array(to_tsvector('english', 'ranked lists')), ' | '), '')) as q (query)
 where c.owner_id = :owner
   and c.tsv @@ q.query
 order by score desc, c.id
 limit 10;

-- The lexical channel must match on the queried terms and nothing else. Printing the
-- rows above shows what happened; this asserts it, so a change in the tsquery parser or
-- the generated column fails the build rather than quietly altering the ranking.
do $$
declare hits text[];
begin
    select array_agg(c.content order by ts_rank(c.tsv, q.query) desc, c.id)
      into hits
      from chunks c, to_tsquery('english', nullif(array_to_string(
           tsvector_to_array(to_tsvector('english', 'ranked lists')), ' | '), '')) as q (query)
     where c.owner_id = '22222222-2222-2222-2222-222222222222'
       and c.tsv @@ q.query;

    if hits is distinct from array['RRF fuses ranked lists'] then
        raise exception 'lexical channel returned %, expected only the matching chunk', hits;
    end if;
end $$;

\echo '--- RetrievalService.hybrid() SQL, verbatim ---'
with dense as (
    select c.id, row_number() over (order by c.embedding <=> mkvec(1.0)) as rank
      from chunks c
     where c.owner_id = :owner and c.embedding is not null
     order by c.embedding <=> mkvec(1.0)
     limit 100
),
lexical as (
    select c.id,
           row_number() over (order by ts_rank(c.tsv, q.query) desc, c.id) as rank
      from chunks c, to_tsquery('english', nullif(array_to_string(
               tsvector_to_array(to_tsvector('english', 'rerank results')), ' | '), '')) as q (query)
     where c.owner_id = :owner and c.tsv @@ q.query
     order by ts_rank(c.tsv, q.query) desc, c.id
     limit 100
),
fused as (
    select coalesce(dense.id, lexical.id) as id,
           coalesce(1.0 / (60 + dense.rank), 0)
         + coalesce(1.0 / (60 + lexical.rank), 0) as score
      from dense full outer join lexical on lexical.id = dense.id
)
select c.content, round(fused.score, 6) as score
  from fused
  join chunks c on c.id = fused.id
 order by fused.score desc, c.id
 limit 10;

-- Fusion has to change something to be worth a second query. Against mkvec(1.0) the
-- dense channel ranks 'RRF fuses ranked lists' first; the lexical query 'rerank results'
-- only matches 'Cross-encoders rerank results'. Appearing in both lists is what lifts
-- the second chunk above the first, and that reordering is the property asserted here.
do $$
declare top text;
begin
    with dense as (
        select c.id, row_number() over (order by c.embedding <=> mkvec(1.0)) as rank
          from chunks c
         where c.owner_id = '22222222-2222-2222-2222-222222222222'
           and c.embedding is not null
         limit 100
    ),
    lexical as (
        select c.id,
               row_number() over (order by ts_rank(c.tsv, q.query) desc, c.id) as rank
          from chunks c, to_tsquery('english', nullif(array_to_string(
               tsvector_to_array(to_tsvector('english', 'rerank results')), ' | '), '')) as q (query)
         where c.owner_id = '22222222-2222-2222-2222-222222222222'
           and c.tsv @@ q.query
         limit 100
    ),
    fused as (
        select coalesce(dense.id, lexical.id) as id,
               coalesce(1.0 / (60 + dense.rank), 0)
             + coalesce(1.0 / (60 + lexical.rank), 0) as score
          from dense full outer join lexical on lexical.id = dense.id
    )
    select c.content into top
      from fused join chunks c on c.id = fused.id
     order by fused.score desc, c.id
     limit 1;

    if top is distinct from 'Cross-encoders rerank results' then
        raise exception 'fusion ranked % first; a chunk in both channels should lead', top;
    end if;
end $$;

\echo '--- HNSW index usage at scale (expects Index Scan using chunks_embedding_hnsw_idx) ---'
insert into chunks (document_id, owner_id, ordinal, content, content_hash, char_start, char_end, embedding)
select 'aaaaaaaa-0000-0000-0000-000000000001', :owner, 1000 + g, 'chunk ' || g, 'h' || g, 0, 10,
       ('[' || array_to_string(array(select random() from generate_series(1,384)), ',') || ']')::vector
from generate_series(1, 5000) g;
analyze chunks;
explain (costs off, summary off)
 select c.id from chunks c
  where c.owner_id = :owner and c.embedding is not null
  order by c.embedding <=> (select embedding from chunks limit 1)
  limit 10;
