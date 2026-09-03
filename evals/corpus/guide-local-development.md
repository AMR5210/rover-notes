# Local development guide

Getting the stack running, and what usually goes wrong the first time.

## Prerequisites

A JDK 21 or newer, a container runtime, and a Python toolchain that can create virtual
environments. Nothing else is installed globally; the build fetches its own dependencies
and the model servers arrive as images.

The two model containers are the slow part of a cold start. They fetch weights on first
run, and on a connection that cannot reach the weight host they will retry for several
minutes per missing file before falling back to whatever is already cached. A first start
that appears to hang for twenty minutes is usually this rather than a deadlock, and the
container logs say which file it is waiting on.

## Bringing the stack up

Start the containers, wait for the database to accept connections, then start the
application. The application applies any pending schema changes at startup, so there is
no separate migration step to remember and no way for the two to drift.

The application uses a development profile that skips token validation and attributes
every request to a single fixed owner. This exists so the endpoints can be exercised
without an identity provider. It must never be active anywhere real, and the default
profile refuses to start without a configured issuer for exactly that reason.

## Seeding something to search

An empty index makes every endpoint look broken in the same way, so the first thing worth
doing is writing a few notes. Indexing is asynchronous: the write returns as soon as the
row commits, and the text becomes searchable a moment later when the background worker
picks it up. A search that returns nothing immediately after a write is usually this, not
a bug, and repeating the search a second later settles it.

## Running the checks

Three suites, and they fail for unrelated reasons. The JVM tests spin up a real database
in a container, so they need the container runtime and will fail with a confusing
initialisation error if it is not running. The Python suite is pure and fast. The
retrieval evaluation needs the whole stack up, including both model servers, because it
scores the running service over HTTP rather than reimplementing any of it.

## Things that go wrong

**A test suite fails with "could not find a valid environment".** The container runtime is
not running. Nothing in the code is wrong.

**Search returns results but every score is identical.** The embedding server started but
failed to load weights and is returning a constant. Check the model server's own health
endpoint rather than the application's.

**The application starts and immediately exits.** Almost always a schema checksum
mismatch, meaning a migration file was edited after being applied. Recreate the database
volume rather than editing the history table.

**Everything is slow.** The model containers and the database are competing for the same
few cores. This is expected on a laptop and is why the latency figures in the evaluation
history state the hardware they were measured on.
