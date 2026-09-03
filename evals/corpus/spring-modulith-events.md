# Application events between modules

Modules communicate by publishing events rather than by calling one another directly.

A listener annotated with @ApplicationModuleListener runs after the publishing
transaction commits and opens a transaction of its own, so a slow consumer never holds
the writer's locks. Delivery is asynchronous on a virtual thread per listener invocation,
which means a listener blocked on HTTP to the inference service occupies no platform
thread.

Event types are narrow and named in the past tense: NoteCreated, NoteIndexed,
ChunkEmbedded, DocumentDeleted. Payloads carry identifiers rather than entities, because
a serialised entity goes stale the moment the underlying row changes and the payload is
read back after a restart.

Failures retry with exponential backoff starting at 5 seconds and stopping after 5
attempts, after which the publication is left incomplete for the sweeper to surface.
Integration tests use Modulith's Scenario API to publish an event and await the resulting
state change, which avoids the fixed sleeps that make asynchronous tests slow and flaky.
