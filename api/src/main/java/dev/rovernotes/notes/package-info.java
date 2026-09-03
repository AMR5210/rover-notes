/**
 * Notes and documents — the system's source of truth.
 *
 * <p>Owns the {@code documents} table and publishes {@link dev.rovernotes.notes.DocumentChanged}
 * whenever content changes. Because Spring Modulith persists application events in the
 * same transaction as the write, that publication <em>is</em> the transactional outbox:
 * a document and its indexing job commit together or not at all. See docs/ARCHITECTURE.md.
 *
 * <p>Chunking, embedding, and indexing are deliberately not here — {@code ingestion}
 * consumes the event.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notes")
package dev.rovernotes.notes;
