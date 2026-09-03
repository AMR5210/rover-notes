package dev.rovernotes.notes;

import java.util.UUID;

/**
 * Published when a document's content changes and needs (re)indexing.
 *
 * <p>Spring Modulith persists this event in the same transaction as the document write,
 * so there is no window in which a document exists without a pending indexing job and
 * no dual-write problem to solve with change-data-capture. See docs/ARCHITECTURE.md.
 *
 * <p>Carries the {@code contentHash} so the consumer can short-circuit before doing any
 * parsing or embedding work.
 */
public record DocumentChanged(
        UUID documentId,
        UUID ownerId,
        String contentHash,
        Kind kind
) {

    public enum Kind { CREATED, UPDATED, DELETED }

    public static DocumentChanged created(Document document) {
        return new DocumentChanged(document.id(), document.ownerId(), document.contentHash(), Kind.CREATED);
    }

    public static DocumentChanged updated(Document document) {
        return new DocumentChanged(document.id(), document.ownerId(), document.contentHash(), Kind.UPDATED);
    }

    public static DocumentChanged deleted(UUID documentId, UUID ownerId) {
        return new DocumentChanged(documentId, ownerId, null, Kind.DELETED);
    }
}
