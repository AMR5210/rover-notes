package dev.rovernotes.notes;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A unit of user content: a typed note, an uploaded PDF, a web clip.
 *
 * <p>{@code contentHash} is the idempotency key for re-indexing. Ingestion compares it
 * before doing any work, so re-importing an unchanged document costs nothing and
 * editing one paragraph of a long document re-embeds one chunk rather than all of them.
 *
 * <p>A null {@code id} means "new" — Postgres generates the UUID on insert.
 *
 * <p>{@code topicId} is where the document sits, and null is the ordinary case rather
 * than a missing value: every document written before topics existed has none, and filing
 * is optional afterwards. It changes nothing about how the document is chunked, embedded
 * or ranked — see docs/ARCHITECTURE.md.
 */
@Table("documents")
public record Document(
        @Id UUID id,
        UUID ownerId,
        UUID topicId,
        String title,
        String sourceType,
        String sourceUri,
        String content,
        String contentHash,
        Instant createdAt,
        Instant updatedAt
) {

    public static Document newNote(UUID ownerId, String title, String content) {
        return newNote(ownerId, null, title, content);
    }

    public static Document newNote(UUID ownerId, UUID topicId, String title, String content) {
        Instant now = Instant.now();
        return new Document(
                null,
                ownerId,
                topicId,
                title,
                SourceType.NOTE,
                null,
                content,
                ContentHash.of(content),
                now,
                now);
    }

    /**
     * A document that arrived as a file rather than being typed.
     *
     * <p>{@code sourceUri} records where the original is kept, so a reader following a
     * citation to page 34 can be handed the file that has a page 34. It is null until
     * there is object storage to put it in; the extracted text stands on its own until
     * then, and a null here is "not stored yet" rather than "not a file".
     */
    public static Document newUpload(UUID ownerId, UUID topicId, String title, String content,
                                     String sourceType, String sourceUri) {
        Instant now = Instant.now();
        return new Document(
                null,
                ownerId,
                topicId,
                title,
                sourceType,
                sourceUri,
                content,
                ContentHash.of(content),
                now,
                now);
    }

    /** The same document, now recording where its original file is kept. */
    public Document withSource(String uri) {
        return new Document(id, ownerId, topicId, title, sourceType, uri, content, contentHash,
                createdAt, updatedAt);
    }

    public Document withContent(String newTitle, String newContent) {
        return new Document(
                id,
                ownerId,
                topicId,
                newTitle,
                sourceType,
                sourceUri,
                newContent,
                ContentHash.of(newContent),
                createdAt,
                Instant.now());
    }

    /**
     * The same document, filed under a different topic — or under none, when null.
     *
     * <p>Separate from {@link #withContent}, and deliberately does not touch
     * {@code updatedAt} or the content hash. Moving a document between topics changes
     * nothing a chunk is built from, so re-embedding it would be work with no result.
     */
    public Document withTopic(UUID newTopicId) {
        return new Document(id, ownerId, newTopicId, title, sourceType, sourceUri, content,
                contentHash, createdAt, updatedAt);
    }

    /** True when the incoming content is byte-identical to what is already stored. */
    public boolean contentUnchanged(String candidate) {
        return contentHash.equals(ContentHash.of(candidate));
    }

    public static final class SourceType {
        public static final String NOTE = "note";
        public static final String PDF = "pdf";
        public static final String WEB = "web";

        private SourceType() {}
    }
}
