package dev.rovernotes.notes;

import java.time.Instant;
import java.util.UUID;

/**
 * A name a document can sit under: "Machine learning", "Rock mechanics".
 *
 * <p>A label and nothing more. It carries no settings, no retrieval behaviour and no
 * hierarchy — searching and asking stay cross-topic, so what a topic changes is what the
 * reader is shown, never what the ranker can reach. See docs/ARCHITECTURE.md.
 *
 * <p>{@code documentCount} is not stored. It is counted by the query that lists topics,
 * because the interface shows it beside every name and a second round trip per topic to
 * fetch it would cost more than the join does.
 */
public record Topic(
        UUID id,
        UUID ownerId,
        String name,
        Instant createdAt,
        long documentCount
) {}
