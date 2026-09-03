# Database operations runbook

Routine work against the primary PostgreSQL instance, and the checks that keep it
predictable.

## Applying a migration

Migrations run inside the application at startup and are forward-only. A deployment that
fails after a migration has applied leaves the schema ahead of the code, which the
expand-and-contract sequence is designed to tolerate: add the new column nullable, ship
the code that writes both, backfill, ship the code that reads the new one, and only then
remove the old. Each of those is a separate release.

Long-lived migrations are the exception worth planning. Adding an index on a populated
table takes a lock that blocks writes for the duration, so anything over a few seconds
is created concurrently, outside the migration path, and recorded afterwards.

## Bloat and vacuum

Autovacuum is left on with default settings. The tables that matter are the ones with
high update churn: the queue table, where a claimed row is updated twice per job, and
the publication registry, where a completed event is updated once and then deleted.

Bloat is checked monthly rather than continuously. The number that matters is dead tuples
as a fraction of live ones; above roughly 20% on the queue table, autovacuum is not
keeping up and its per-table threshold needs lowering rather than a manual vacuum, which
only defers the question.

## Rebuilding an index

Vector indexes degrade differently from B-trees. A graph index accumulates deleted
entries that still occupy nodes, so recall falls quietly as deletions accumulate while
query time stays flat. There is no equivalent of a bloat query for it; the signal is a
drop in measured retrieval quality with no code change, which is one reason the eval
suite runs against a live instance rather than a fixture.

Rebuilding is done by building a replacement under a temporary name and swapping, which
keeps the old index serving queries throughout. The build is single-threaded per index
and takes roughly a minute per hundred thousand vectors on this hardware.

## Replicas and promotion

There is one replica, used for backup verification rather than read traffic. Splitting
reads across it would mean handling replication lag in the retrieval path, and a search
that misses a document written two seconds ago is a worse failure than a slightly busier
primary.

Promotion is manual and expected to take under ten minutes, most of which is waiting for
the replica to finish replaying. The runbook step people forget is repointing the
connection string before restarting the application, not after.

## Verifying a backup

A backup that has never been restored is a hypothesis. Restoration into a scratch
instance runs weekly and is scored by three checks: the row counts match within the
window, the vector extension loads and an index scan returns, and the newest document
predates the snapshot by less than the backup interval.
