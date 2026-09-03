# Model registry

Every model the system calls is named in one table rather than in configuration spread
across modules.

A row records the logical role (embedding, reranking, synthesis, classification, judging)
together with the provider, the exact model identifier, and the date it became active.
Floating aliases such as "latest" are not used, because a model that changes underneath a
pinned configuration invalidates the committed baseline without any commit to point at.

Each chunk stores the identifier of the model that produced its vector. A model change
inserts a new row, leaves the previous one active for reads, and backfills in the
background; a chunk is served from the new index only once its vector matches the active
identifier.

Rolling back is a matter of flipping which row is active, provided both sets of vectors
are still present. Deleting superseded vectors is a separate and deliberate step, taken
after an eval run confirms the replacement, and it is what makes a model upgrade
reversible for the duration of the backfill.
