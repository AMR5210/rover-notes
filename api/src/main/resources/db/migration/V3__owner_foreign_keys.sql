-- Rover Notes 2.0 — owner_id becomes a foreign key
--
-- Under docs/ARCHITECTURE.md identity lived in another system, so owner_id was a foreign identifier
-- and isolation rested entirely on every query remembering to filter by it. docs/ARCHITECTURE.md moved
-- identity here, which makes the constraint possible: the token subject is users.id, and
-- that is the value these columns already hold.
--
-- What this buys is not tidiness. A query that forgets its owner filter is still a bug,
-- but a write that invents an owner is now refused by the database rather than producing
-- rows nobody can reach and nobody notices.
--
-- Deletion behaviour differs by table and the difference is deliberate:
--
--   cascade   for a person's own content. Deleting an account removes what it wrote,
--             which is the behaviour a deletion request expects.
--   set null  for llm_usage. The cost is real and was incurred whatever happens to the
--             account afterwards, so the row survives with its attribution removed. The
--             column is already nullable for exactly this reason: not every model call
--             is made on behalf of someone.
--
-- Applying this to a database holding rows whose owner has no account will fail, which is
-- intended: those rows are unreachable and inventing accounts to make a constraint pass
-- would defeat it. A local database from before this migration needs recreating once.

alter table documents
    add constraint documents_owner_fk
    foreign key (owner_id) references users (id) on delete cascade;

alter table chunks
    add constraint chunks_owner_fk
    foreign key (owner_id) references users (id) on delete cascade;

alter table entities
    add constraint entities_owner_fk
    foreign key (owner_id) references users (id) on delete cascade;

alter table user_settings
    add constraint user_settings_owner_fk
    foreign key (owner_id) references users (id) on delete cascade;

alter table llm_usage
    add constraint llm_usage_owner_fk
    foreign key (owner_id) references users (id) on delete set null;

-- PostgreSQL does not index a foreign key automatically, and a cascading delete scans the
-- referencing side once per row removed. documents, chunks, llm_usage and user_settings
-- are already covered by indexes leading with owner_id from V1; entities is not.
create index entities_owner_fk_idx on entities (owner_id);
