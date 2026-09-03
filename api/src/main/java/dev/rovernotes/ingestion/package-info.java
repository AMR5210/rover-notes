/**
 * Ingestion pipeline: parse, chunk, contextualize, embed, index.
 *
 * <p>Consumes {@code DocumentChanged} and drives the outbox worker. The pipeline is
 * idempotent at chunk granularity via SHA-256 content hashing, so re-importing an
 * unchanged file does no embedding work and editing one paragraph of a long document
 * re-embeds one chunk rather than all of them.

 */
@org.springframework.modulith.ApplicationModule(displayName = "Ingestion")
package dev.rovernotes.ingestion;
