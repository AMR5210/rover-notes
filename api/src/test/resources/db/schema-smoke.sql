\set ON_ERROR_STOP on
-- seed one document + three chunks with random 384-dim embeddings
insert into documents (id, owner_id, title, content, content_hash)
values ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222',
        'auth_service.go notes','ConnectionPoolTimeoutException when the pool is saturated','h1');

insert into chunks (document_id, owner_id, ordinal, content, content_hash, char_start, char_end, embedding)
select '11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222', g,
       case g when 1 then 'The connection pool timed out under load: ConnectionPoolTimeoutException'
              when 2 then 'Retrieval latency budget is dominated by cross-encoder reranking'
              else 'Reciprocal rank fusion merges dense and sparse candidate lists' end,
       'ch'||g, 0, 50,
       ('['||array_to_string(array(select random() from generate_series(1,384)),',')||']')::vector
from generate_series(1,3) g;

\echo '--- 1. generated tsvector column populated ---'
select ordinal, (tsv is not null) as tsv_ok from chunks order by ordinal;

\echo '--- 2. dense (HNSW / cosine) top-2 ---'
select ordinal from chunks
 where owner_id='22222222-2222-2222-2222-222222222222'
 order by embedding <=> ('['||array_to_string(array(select random() from generate_series(1,384)),',')||']')::vector
 limit 2;

\echo '--- 3. lexical (tsvector) known-item query ---'
select ordinal, round(ts_rank_cd(tsv, q)::numeric,4) as rank
  from chunks, websearch_to_tsquery('english','ConnectionPoolTimeoutException') q
 where tsv @@ q order by rank desc;

\echo '--- 4. pg_trgm filename route ---'
select title, round(similarity(title,'auth_service.go')::numeric,3) as sim
  from documents where title % 'auth_service.go';

\echo '--- 5. RRF fusion over both channels (k=60) ---'
with qv as (select ('['||array_to_string(array(select random() from generate_series(1,384)),',')||']')::vector v),
dense as (select id, row_number() over () r from chunks, qv
          where owner_id='22222222-2222-2222-2222-222222222222'
          order by embedding <=> qv.v limit 10),
sparse as (select c.id, row_number() over (order by ts_rank_cd(c.tsv,q) desc) r
           from chunks c, websearch_to_tsquery('english','rank fusion connection') q
           where c.tsv @@ q limit 10)
select coalesce(d.id,s.id) as chunk_id,
       round((coalesce(1.0/(60+d.r),0) + coalesce(1.0/(60+s.r),0))::numeric,6) as rrf
  from dense d full outer join sparse s on d.id=s.id
 order by rrf desc;

\echo '--- 6. outbox SKIP LOCKED claim ---'
insert into outbox (aggregate_type, aggregate_id, event_type)
select 'document','11111111-1111-1111-1111-111111111111','document.created' from generate_series(1,3);
begin;
select id, event_type from outbox
 where status='pending' and next_attempt_at <= now()
 order by id for update skip locked limit 2;
commit;

\echo '--- 7. seeded model registry ---'
select id, tier, cost_in_per_mtok, supports_tools from models order by tier;
