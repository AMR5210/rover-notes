package dev.rovernotes.notes;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final DocumentRepository documents;
    private final DocumentPages pages;
    private final DocumentParser parser;
    private final TopicService topics;
    private final ObjectProvider<FileStore> files;
    private final ApplicationEventPublisher events;

    NoteService(DocumentRepository documents,
                DocumentPages pages,
                DocumentParser parser,
                TopicService topics,
                ObjectProvider<FileStore> files,
                ApplicationEventPublisher events) {
        this.documents = documents;
        this.pages = pages;
        this.parser = parser;
        this.topics = topics;
        this.files = files;
        this.events = events;
    }

    /**
     * Ingests an uploaded PDF: extract it, store the text, record where its pages are.
     *
     * <p>The document is saved with the extracted text, so everything downstream —
     * chunking, embedding, retrieval, citation spans — treats it exactly as it treats a
     * typed note. The only thing that knows a file was involved is the page table, and
     * the only thing that reads it is the step that turns a citation back into something
     * a reader can look up.
     *
     * <p>Pages are written in the same transaction as the document and before the change
     * is published. Indexing runs off that event, so a citation cannot be produced for a
     * document whose pages have not been recorded yet.
     */
    @Transactional
    public Document createFromPdf(UUID ownerId, String title, String filename, byte[] file) {
        return createFromPdf(ownerId, null, title, filename, file);
    }

    @Transactional
    public Document createFromPdf(UUID ownerId, UUID topicId, String title, String filename,
                                  byte[] file) {
        topics.requireOwned(ownerId, topicId);
        DocumentParser.Parsed parsed = parser.parsePdf(filename, file);

        Document saved = documents.save(Document.newUpload(
                ownerId, topicId, title, parsed.text(), Document.SourceType.PDF, null));
        pages.replace(saved.id(), parsed.pageRanges());

        // The original is kept so a reader following a citation to page 34 can be handed
        // the document that has a page 34. Best effort on purpose: the answer is built
        // from the extracted text, so storage being unavailable costs the file rather
        // than the ingest, and failing the upload would be the larger loss.
        String uri = store(ownerId, saved.id(), file);
        if (uri != null) {
            saved = documents.save(saved.withSource(uri));
        }

        if (parsed.truncated()) {
            log.warn("document {} was truncated at the page limit; {} pages indexed",
                    saved.id(), parsed.pages().size());
        }
        if (parsed.unreadable()) {
            // A scan with no text layer that OCR could not read either. It is stored,
            // because the original is still worth keeping and the caller can see the
            // upload succeeded — but it will index to nothing, and saying so here is the
            // difference between a known limitation and a search that mysteriously never
            // returns it.
            log.warn("document {} produced no text from any of its {} pages; "
                            + "it will not be retrievable",
                    saved.id(), parsed.pages().size());
        }

        events.publishEvent(DocumentChanged.created(saved));
        return saved;
    }

    /**
     * Ingests a web page: fetch it, keep the readable part, record where it came from.
     *
     * <p>Stored as an ordinary document like an uploaded PDF, so nothing downstream needs
     * a web-shaped path. {@code sourceUri} is the URL the page was finally served from
     * rather than the one supplied, since after a redirect that is what a reader
     * following the citation should open.
     *
     * <p>No page ranges: a web page has none, and a citation into one keeps a null page
     * exactly as a typed note does.
     */
    @Transactional
    public Document createFromUrl(UUID ownerId, String url) {
        return createFromUrl(ownerId, null, url);
    }

    @Transactional
    public Document createFromUrl(UUID ownerId, UUID topicId, String url) {
        topics.requireOwned(ownerId, topicId);
        DocumentParser.Clipped clipped = parser.clipUrl(url);

        Document saved = documents.save(Document.newUpload(
                ownerId, topicId, clipped.title(), clipped.text(),
                Document.SourceType.WEB, clipped.url()));

        events.publishEvent(DocumentChanged.created(saved));
        return saved;
    }

    /**
     * Puts the original in object storage, or returns null if it could not be kept.
     *
     * <p>Failures are logged rather than raised. The document is already saved and
     * indexed by this point, and unwinding that because a bucket was unreachable would
     * discard work that succeeded in order to report a feature that degrades cleanly.
     */
    private String store(UUID ownerId, UUID documentId, byte[] file) {
        FileStore store = files.getIfAvailable();
        if (store == null) {
            return null;
        }
        try {
            return store.put(ownerId, documentId, "application/pdf", file);
        } catch (RuntimeException e) {
            log.warn("could not keep the original of document {}: {}", documentId,
                    e.getMessage());
            return null;
        }
    }

    @Transactional
    public Document create(UUID ownerId, String title, String content) {
        return create(ownerId, null, title, content);
    }

    @Transactional
    public Document create(UUID ownerId, UUID topicId, String title, String content) {
        topics.requireOwned(ownerId, topicId);
        Document saved = documents.save(Document.newNote(ownerId, topicId, title, content));
        events.publishEvent(DocumentChanged.created(saved));
        return saved;
    }

    /** Updates a note and leaves it filed where it already is. */
    @Transactional
    public Document update(UUID ownerId, UUID id, String title, String content) {
        return update(ownerId, id, title, content, require(ownerId, id).topicId());
    }

    /**
     * Updates a note, and files it under {@code topicId} — or unfiles it, when null.
     *
     * <p>Updating with byte-identical content is a no-op: no write, no event, no
     * re-embedding. This is the cheapest half of ingestion idempotency — the other half
     * lives at chunk granularity, so editing one paragraph of a long document re-embeds
     * one chunk rather than all of them.
     *
     * <p>Moving a document between topics is not a content change and does not publish
     * one. Nothing downstream of {@link DocumentChanged} reads the topic: chunks carry
     * the text, its offsets and the owner, so re-indexing after a move would re-embed a
     * document to arrive at the rows it already has. The write still happens, so the move
     * is durable; what it skips is the work behind it.
     */
    @Transactional
    public Document update(UUID ownerId, UUID id, String title, String content, UUID topicId) {
        topics.requireOwned(ownerId, topicId);
        Document existing = require(ownerId, id);

        boolean contentSame = existing.title().equals(title) && existing.contentUnchanged(content);
        boolean topicSame = Objects.equals(existing.topicId(), topicId);

        if (contentSame && topicSame) {
            log.debug("document {} unchanged, skipping re-index", id);
            return existing;
        }
        if (contentSame) {
            return documents.save(existing.withTopic(topicId));
        }

        Document saved = documents.save(existing.withContent(title, content).withTopic(topicId));
        events.publishEvent(DocumentChanged.updated(saved));
        return saved;
    }

    @Transactional
    public void delete(UUID ownerId, UUID id) {
        Document existing = require(ownerId, id);
        documents.delete(existing);
        // Chunks cascade at the database level; the event lets other modules drop
        // derived state they own (entity mentions, cached answers).
        events.publishEvent(DocumentChanged.deleted(id, ownerId));
    }

    @Transactional(readOnly = true)
    public Document get(UUID ownerId, UUID id) {
        return require(ownerId, id);
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID ownerId, int limit, int offset) {
        return list(ownerId, TopicFilter.all(), limit, offset);
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID ownerId, TopicFilter filter, int limit, int offset) {
        if (filter.topicId() != null) {
            return documents.findByOwnerAndTopic(ownerId, filter.topicId(), limit, offset);
        }
        return filter.unfiledOnly()
                ? documents.findByOwnerWithoutTopic(ownerId, limit, offset)
                : documents.findByOwner(ownerId, limit, offset);
    }

    @Transactional(readOnly = true)
    public long count(UUID ownerId) {
        return count(ownerId, TopicFilter.all());
    }

    @Transactional(readOnly = true)
    public long count(UUID ownerId, TopicFilter filter) {
        if (filter.topicId() != null) {
            return documents.countByOwnerAndTopic(ownerId, filter.topicId());
        }
        return filter.unfiledOnly()
                ? documents.countByOwnerWithoutTopic(ownerId)
                : documents.countByOwner(ownerId);
    }

    private Document require(UUID ownerId, UUID id) {
        return documents.findByIdAndOwner(id, ownerId)
                .orElseThrow(() -> new NoSuchElementException("No document " + id));
    }
}
