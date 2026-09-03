# Backup and restore

The recovery plan separates what has to be restored from what can be recomputed.

Base backups are taken nightly to object storage with continuous WAL archiving, giving
point-in-time recovery to any moment in the last 14 days. The recovery point objective is
5 minutes of WAL and the recovery time objective is 1 hour, both confirmed by a quarterly
restore drill into a scratch instance rather than assumed from the configuration.

Documents and their uploaded source files are the only irreplaceable data. Chunks,
embeddings, and index structures are derived, and are rebuilt from the documents table by
resetting the pending flag on their indexing work. A full re-embed of 300,000 chunks
takes about 40 minutes on the CPU inference service.

Backups still cover the whole database rather than a selected set of tables, because a
consistent snapshot is simpler to reason about than a partial one and restoring derived
data costs less than proving which rows were derived when. The vector index is rebuilt
after a restore rather than carried inside the dump.
