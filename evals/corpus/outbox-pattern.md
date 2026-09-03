# The transactional outbox

Module events are persisted before they are delivered, so a committed write always has
its downstream work recorded alongside it.

Spring Modulith 2.1 supplies a JDBC event publication registry. Publishing an event from
inside a transaction inserts a row into event_publication carrying the listener
identifier, the serialised payload, and a null completion_date. The listener sets that
column when it returns, and a row still incomplete after a crash is republished on
startup.

Incomplete publications are also swept on a 60 second schedule, which covers a listener
that failed without throwing. Completed rows are purged after 7 days so the table stays
small enough to remain in cache.

The guarantee this provides is at-least-once delivery of an event to a named listener,
which makes idempotent listeners a requirement rather than a nicety: the embedding
listener keys on chunk hash and returns early when the vector already exists. Ordering
between different listeners is not guaranteed and nothing in the pipeline depends on it.
