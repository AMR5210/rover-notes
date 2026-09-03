package dev.rovernotes.notes;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import dev.rovernotes.CurrentOwner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
class NoteController {

    private final NoteService notes;
    private final CurrentOwner owner;
    private final ObjectProvider<FileStore> files;

    NoteController(NoteService notes, CurrentOwner owner, ObjectProvider<FileStore> files) {
        this.notes = notes;
        this.owner = owner;
        this.files = files;
    }

    /**
     * The library, optionally narrowed to one topic.
     *
     * <p>{@code topic} takes a topic id, or the literal {@code none} for the documents
     * that have not been filed. Absent means everything, which is what the page shows
     * when it opens — a library is a list before it is a set of folders.
     */
    @GetMapping
    PageResponse list(@RequestParam(defaultValue = "50") int limit,
                      @RequestParam(defaultValue = "0") int offset,
                      @RequestParam(required = false) String topic) {
        int capped = Math.clamp(limit, 1, 200);
        UUID ownerId = owner.id();
        TopicFilter filter = filterFor(topic);
        List<NoteResponse> items = notes.list(ownerId, filter, capped, Math.max(offset, 0))
                .stream()
                .map(NoteResponse::from)
                .toList();
        return new PageResponse(items, notes.count(ownerId, filter), capped, offset);
    }

    /**
     * Reads the {@code topic} query parameter.
     *
     * <p>{@code none} is a word rather than an empty value because an empty one cannot be
     * told apart from the parameter being absent, and those mean opposite things here:
     * every document, against only the unfiled ones.
     */
    private static TopicFilter filterFor(String topic) {
        if (topic == null || topic.isBlank()) {
            return TopicFilter.all();
        }
        if (topic.equals("none")) {
            return TopicFilter.unfiled();
        }
        try {
            return TopicFilter.of(UUID.fromString(topic));
        } catch (IllegalArgumentException e) {
            throw new UnknownTopicFilter();
        }
    }

    @GetMapping("/{id}")
    NoteResponse get(@PathVariable UUID id) {
        return NoteResponse.from(notes.get(owner.id(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    NoteResponse create(@RequestBody NoteRequest request) {
        return NoteResponse.from(notes.create(
                owner.id(), request.topicId(), request.title(), request.content()));
    }

    /**
     * Ingests an uploaded PDF.
     *
     * <p>Its own endpoint rather than a content type on the existing one, because what a
     * caller sends is different in kind: a file and a name, not a title and a body. The
     * title falls back to the filename, which is what a person uploading a report would
     * have called it anyway.
     *
     * <p>Counted against the ingest limit like any other write to this path — see
     * {@code RateLimitFilter}, which buckets by method rather than by media type, so this
     * needed no change there.
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    NoteResponse upload(@RequestPart("file") MultipartFile file,
                        @RequestParam(value = "title", required = false) String title,
                        @RequestParam(value = "topicId", required = false) UUID topicId)
            throws IOException {
        if (file.isEmpty()) {
            throw new EmptyUpload();
        }
        String filename = file.getOriginalFilename() == null
                ? "upload.pdf"
                : file.getOriginalFilename();
        String documentTitle = (title == null || title.isBlank()) ? filename : title;

        return NoteResponse.from(notes.createFromPdf(
                owner.id(), topicId, documentTitle, filename, file.getBytes()));
    }

    /**
     * The original file a document was ingested from.
     *
     * <p>What a page number is for: an answer citing page 34 is only checkable if the
     * reader can open the document that has a page 34. The extracted text is already
     * available through {@code GET /api/notes/{id}}; this is the file it came from.
     *
     * <p>Ownership is enforced by looking the document up as its owner first. Serving
     * from the store by key alone would let anyone who could guess two UUIDs read another
     * account's uploads, and the storage layer has no idea who is asking.
     */
    @GetMapping("/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable UUID id) {
        Document document = notes.get(owner.id(), id);
        if (document.sourceUri() == null) {
            // Ingested while storage was unavailable, or not a file at all.
            return ResponseEntity.notFound().build();
        }

        return files.getIfAvailable() == null
                ? ResponseEntity.notFound().build()
                : files.getObject().get(owner.id(), id)
                        .map(bytes -> ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_PDF)
                                // inline, so following a citation opens the document
                                // rather than downloading it to be found later.
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                        ContentDisposition.inline()
                                                .filename(document.title())
                                                .build()
                                                .toString())
                                .body(bytes))
                        .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Captures a web page as a document.
     *
     * <p>The URL is fetched server-side, which is why the parsing service checks every
     * address it resolves to before connecting: a caller who can make this service
     * retrieve an arbitrary URL can otherwise reach whatever the service can and they
     * cannot. A refused URL comes back as 422 for the same reason an unreadable file
     * does — the request was well formed and what failed was the thing it named.
     */
    @PostMapping("/clip")
    @ResponseStatus(HttpStatus.CREATED)
    NoteResponse clip(@RequestBody @Valid ClipRequest request) {
        return NoteResponse.from(notes.createFromUrl(
                owner.id(), request.topicId(), request.url()));
    }

    @PutMapping("/{id}")
    NoteResponse update(@PathVariable UUID id, @RequestBody NoteRequest request) {
        return NoteResponse.from(notes.update(
                owner.id(), id, request.title(), request.content(), request.topicId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        notes.delete(owner.id(), id);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    /**
     * A file this parser cannot read is the caller's mistake, not a server fault.
     *
     * <p>422 rather than 400: the request was well formed and the upload was accepted,
     * and what failed was the content of the file. A 500 here would put an operator on
     * the trail of a service that is working.
     */
    @ExceptionHandler(DocumentParser.UnreadableFile.class)
    ResponseEntity<Map<String, String>> unreadable(DocumentParser.UnreadableFile e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "unreadable_file", "detail", e.getMessage()));
    }

    /**
     * The parsing service is down, which is not something the caller did.
     *
     * <p>503 with a sentence rather than the 500 this produced before. A file that cannot
     * be read right now can be read once the service returns, and the difference between
     * "this file is bad" and "come back shortly" is the whole content of the message.
     */
    @ExceptionHandler(DocumentParser.ParsingUnavailable.class)
    ResponseEntity<Map<String, String>> parsingDown() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "parsing_unavailable",
                        "detail", "Documents cannot be read just now. "
                                + "The service that extracts their text is not responding. "
                                + "Try again shortly."));
    }

    @ExceptionHandler(EmptyUpload.class)
    ResponseEntity<Map<String, String>> emptyUpload() {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "empty_upload", "detail", "the uploaded file has no bytes"));
    }

    @ExceptionHandler(UnknownTopicFilter.class)
    ResponseEntity<Map<String, String>> unknownTopicFilter() {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_topic_filter",
                        "detail", "topic must be a topic id or 'none'"));
    }

    /** An upload with no bytes, which would otherwise be stored as an empty document. */
    static class EmptyUpload extends RuntimeException {}

    /** The {@code topic} query parameter was neither a UUID nor {@code none}. */
    static class UnknownTopicFilter extends RuntimeException {}

    // ------------------------------------------------------------------ payloads

    record ClipRequest(
            @NotBlank @Size(max = 2048) String url,
            UUID topicId
    ) {}

    /** {@code topicId} is optional throughout: null files the document nowhere. */
    record NoteRequest(
            @NotBlank @Size(max = 512) String title,
            @NotBlank String content,
            UUID topicId
    ) {}

    record NoteResponse(
            UUID id,
            String title,
            String content,
            String sourceType,
            UUID topicId,
            Instant createdAt,
            Instant updatedAt
    ) {
        static NoteResponse from(Document d) {
            return new NoteResponse(
                    d.id(), d.title(), d.content(), d.sourceType(), d.topicId(),
                    d.createdAt(), d.updatedAt());
        }
    }

    record PageResponse(List<NoteResponse> items, long total, int limit, int offset) {}
}
