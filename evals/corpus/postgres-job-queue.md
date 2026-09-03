# The job queue

Ingestion is asynchronous. Documents are parsed, chunked, embedded, and indexed after
the write returns.

The queue is a Postgres table claimed with SELECT ... FOR UPDATE SKIP LOCKED, woken by
LISTEN/NOTIFY rather than polling. Multiple workers claim disjoint batches with no
coordination between them.

The outbox row is written in the same transaction as the document, so a document can
never exist without its pending indexing work. Reaching the same guarantee with an
external broker requires change-data-capture.

Replay is the usual argument for a log-structured broker such as Kafka. It does not
apply here because the source of truth is the documents table, not an event stream:
reprocessing the corpus after a chunking change is an UPDATE that resets status to
pending.

Peak load is roughly 100 jobs per second during a bulk import. The design holds to
around 1000 jobs per second, beyond which row contention makes a dedicated broker
worthwhile.
