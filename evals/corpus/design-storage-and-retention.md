# Storage and retention

Where bytes live, how long they stay, and what is recomputable.

## Two kinds of data

Original uploads are irreplaceable and go to object storage. Everything derived from them
(extracted text, pieces, vectors, extracted entities) lives in the database and can be
rebuilt from the original by rerunning the pipeline. That split decides the backup policy
on its own: the object store needs versioning and a long retention, and the database needs
a recent snapshot and a rebuild path.

Text written directly rather than uploaded has no original, so for those the database row
is the irreplaceable copy. This asymmetry is easy to forget and is the reason the backup
verification checks row counts rather than only checking that a restore completes.

## Naming and immutability

Objects are addressed by a hash of their content rather than by filename. Two users
uploading the same file store one object, a re-upload after an edit stores a new one, and
nothing is ever overwritten in place. The cost is that deleting a user's file cannot be a
single delete; it is a reference count, and the object goes only when the last reference
does.

## Retention

Derived data follows the document: deleting a document removes its pieces and vectors in
the same transaction. Originals are retained for thirty days after the last reference is
dropped, which is a compromise between "a deletion should mean something" and "the most
common support request is an accidental deletion".

Usage records are different. They are aggregated to a daily figure per owner after ninety
days and the per-request rows are discarded. The detail is useful for a fortnight, and
keeping it indefinitely turns a spend log into the largest table in the database.

## Cold starts and rebuilds

A full rebuild from originals is the disaster path and is deliberately kept exercised: the
same code path runs whenever a chunking or model change requires reindexing, so it is
never a piece of untested recovery machinery. Rebuild throughput is bounded by the
embedding server rather than by the database.

## Sizing

At a hundred thousand pieces the vector data is roughly 150 megabytes at 384 dimensions in
single precision, and the graph index roughly the same again. Text and its search vector
are comparable. That is small enough that the entire working set stays in memory on a
modest instance, which is the assumption behind every latency number recorded so far, and
the first assumption to revisit if those numbers move without a code change.
