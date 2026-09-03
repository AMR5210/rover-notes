-- Where a document sits, for the reader rather than for retrieval.
--
-- The corpus has been one flat list since V1, on the reasoning that retrieval finds a
-- passage wherever it is and a folder is a worse index than a ranking function. That
-- holds for answering a question and does not hold for the question that comes before
-- one: somebody researching two unrelated subjects at once wants to see which of them a
-- document belongs to, and no ranking answers that.
--
-- One topic per document, not many-to-many. A tag set is the more general model and the
-- general model is not the one being asked for — the requirement is where a document
-- sits, and a document sits in one place. A join table would also make "documents with
-- no topic" a not-exists subquery rather than a null check, and that is the query the
-- interface runs most, because every document written before this migration is in it.
--
-- Nothing here touches chunks, embeddings or the retrieval SQL. Search and answers stay
-- cross-topic: a topic is a filter the reader may apply, not a partition that hides
-- passages from the ranker. See docs/ARCHITECTURE.md.

create table topics (
    id         uuid        primary key default gen_random_uuid(),
    owner_id   uuid        not null references users (id) on delete cascade,

    -- Free text rather than a slug. This is a label a person types and reads back, and
    -- the only thing the system does with it is show it and compare it for uniqueness.
    name       text        not null,

    created_at timestamptz not null default now(),

    -- Two topics of the same name under one owner are indistinguishable in the
    -- interface, so the second one is a mistake the database can refuse. Scoped to the
    -- owner, because names are personal: two accounts may both have a "Retrieval".
    constraint topics_owner_name_unique unique (owner_id, name),

    -- Not redundant with the primary key, despite containing it. It is the target the
    -- composite foreign key below references, and PostgreSQL requires a unique
    -- constraint on exactly the referenced column pair.
    constraint topics_id_owner_unique unique (id, owner_id),

    constraint topics_name_not_blank check (length(btrim(name)) > 0)
);

-- The cascade above scans this side once per account deleted; the unique constraint on
-- (owner_id, name) leads with owner_id and covers it, so no separate index is needed.

alter table documents add column topic_id uuid;

-- Referencing (id, owner_id) rather than id alone is what makes a topic's scope real. A
-- foreign key to the primary key would accept another account's topic, leaving a
-- document labelled with a name its owner cannot see and did not choose. Filing it under
-- the pair means the database refuses that, in the same way V3 stopped rows being
-- written for owners who do not exist.
--
-- The column list on the action is why this can be a composite key at all: an unqualified
-- `set null` nulls every referencing column, and owner_id is `not null`, so deleting a
-- topic would fail on its own constraint. `set null (topic_id)` — PostgreSQL 15 and
-- later — clears the topic and leaves the document where it was.
alter table documents
    add constraint documents_topic_fk
    foreign key (topic_id, owner_id) references topics (id, owner_id)
    on delete set null (topic_id);

-- Leading with topic_id serves two readers. Deleting a topic has to find the documents
-- that point at it, and PostgreSQL does not index a foreign key automatically. Listing a
-- topic's documents then arrives in the order the library shows them without a sort,
-- since a topic id already implies its owner and the owner filter is a recheck rather
-- than a scan.
create index documents_topic_idx on documents (topic_id, updated_at desc);
