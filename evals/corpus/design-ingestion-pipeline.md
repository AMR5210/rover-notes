# Ingestion pipeline design

How a written document becomes retrievable, and what happens when a step fails.

## The path a document takes

A write commits the document row and an event in the same transaction. A background
listener picks the event up, fetches the current body, splits it, embeds the pieces, and
replaces the stored chunks for that document in one statement. The write returns before
any of that happens, so the endpoint stays fast and the indexing work is free to be slow.

Publishing the event inside the write transaction is the whole point. If the event were
sent afterwards, a crash between commit and send would leave a document that exists and
is not searchable, with nothing to detect it. Persisting the event alongside the row means
the work is recorded even if the process dies before doing it, and is replayed on restart.

## Idempotency

Reindexing is a replace rather than an append, so running it twice produces the same
result as running it once. This matters more than it sounds: the delivery guarantee on
this path admits a repeat, and an append-based design would silently double every chunk.

Each chunk carries a hash of its own text. That hash is what ties a stored chunk to its
content independently of the identifier it was assigned, and it is what makes ranking
stable across reingests: a tie broken on a generated identifier reorders every time the
document is reindexed, which turns a quality measurement into a coin flip.

## Failure and retry

A failing listener leaves its event unresolved, and unresolved events are replayed when
the application restarts. There is no exponential backoff on this path because the
failures it sees are not transient in the useful sense, because a model server that is down stays
down for tens of seconds, and a document that throws will throw again.

The case that needs care is a document that always fails. Without a limit it is retried on
every restart forever. The limit is a count on the event record; past it, the event is
marked failed and left for inspection rather than deleted, because the interesting
question is which document it was.

## Batching

Text is embedded in batches, bounded by what the model server accepts in one request
rather than by what it can process efficiently. Exceeding that bound is rejected outright,
and the rejection is easy to mistake for a quality problem because the client falls back
to a degraded path rather than failing loudly. The bound is therefore configuration rather
than a constant, and it is set below the server's default rather than equal to it.

## What is not in the pipeline yet

Annotating each piece with a short description of its place in the document before
embedding it. The technique is well supported in the literature and needs a language model
call per piece, which makes it the first stage whose cost is measured in money rather than
milliseconds. It is also the first stage that cannot be evaluated on a corpus whose
documents are single pieces, which is a measurement problem before it is a cost one.
