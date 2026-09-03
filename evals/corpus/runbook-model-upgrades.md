# Model upgrade runbook

Changing an embedding model is a data migration, not a configuration change. This is the
sequence that keeps search working while it happens.

## Why it is not a configuration change

Vectors from two different models are not comparable. Distances between them are
arithmetically valid and semantically meaningless, so a search after a partial migration
returns results, ranks them confidently, and is wrong. Nothing errors. Quality collapses
while every dashboard stays green, which makes this the most dangerous routine change in
the system.

If dimensions differ the failure is at least loud: the column rejects the write. Equal
dimensions with different training is the quiet case and the one to plan for.

## Deciding whether to move

A candidate model needs to be better on this corpus, not on a public leaderboard.
Benchmark scores are measured on document collections that resemble somebody else's
problem, and the gap between two models on a public benchmark routinely fails to appear on
a specific corpus.

The decision procedure is to index a copy of the corpus with the candidate, score it
against the same golden set, and compare the two runs pairwise. A model that wins by less
than the test set can resolve is not a reason to pay for a migration.

## The migration sequence

Add a second vector column rather than replacing the first, so both models are available
at once and the change is reversible without a restore. Backfill the new column in
batches, oldest first, with the application still reading the old one. Backfill throughput
on this hardware is roughly a thousand chunks per minute, dominated by the model server.

Build the index on the new column only after the backfill completes. Building it first
means maintaining an index during a bulk write, which is slower and leaves it more
fragmented than a single build afterwards.

Cut over by changing which column the query reads, which is one configuration value and
takes effect on restart. Keep the old column and its index for at least one full
evaluation cycle; the cost is disk, and the benefit is that a reversal is a restart rather
than an incident.

## Verifying the cutover

Three checks, in order. The dimensions reported by the model server match the column.
Every chunk has a non-null vector in the new column, since a partial backfill is
indistinguishable from a complete one at query time. And the evaluation suite reproduces
the score measured during the decision, on the live instance rather than a copy.

A drop between the decision run and the cutover run means something differs between them
that nobody intended (a truncation limit, a normalisation setting, a pooling mode) and
that difference is worth finding before traffic sees it.

## Reranking models

Cross-encoders are simpler to change because they store nothing. A rerank model reads a
query and a passage and returns a score, so swapping one is genuinely a configuration
change and can be evaluated in an afternoon. The cost is entirely latency, and latency is
measured on the hardware that will serve it rather than assumed from parameter count.
