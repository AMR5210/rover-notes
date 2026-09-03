package dev.rovernotes.notes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import dev.rovernotes.CurrentOwner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The topics a reader can file documents under.
 *
 * <p>Separate from {@code /api/notes} because a topic outlives the documents in it: it is
 * created before the first one is filed and survives the last one being deleted. Folding
 * it into the note payload would make an empty topic something the system could not
 * represent.
 */
@RestController
@RequestMapping("/api/topics")
class TopicController {

    private final TopicService topics;
    private final CurrentOwner owner;

    TopicController(TopicService topics, CurrentOwner owner) {
        this.topics = topics;
        this.owner = owner;
    }

    @GetMapping
    List<TopicResponse> list() {
        return topics.list(owner.id()).stream().map(TopicResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TopicResponse create(@RequestBody @Valid TopicRequest request) {
        return TopicResponse.from(topics.create(owner.id(), request.name()));
    }

    @PutMapping("/{id}")
    TopicResponse rename(@PathVariable UUID id, @RequestBody @Valid TopicRequest request) {
        return TopicResponse.from(topics.rename(owner.id(), id, request.name()));
    }

    /**
     * Removes the topic. The documents in it are kept, unfiled.
     *
     * <p>204 with no body, like deleting a note: there is nothing left to describe, and
     * the count of documents that came loose is already in the list the caller refreshes.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        topics.delete(owner.id(), id);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    /**
     * 409 rather than 400: the request is well formed and would have been accepted a
     * moment ago. What it collides with is a row, and the caller's next move is to use
     * the topic that already exists rather than to correct what they sent.
     */
    @ExceptionHandler(TopicService.DuplicateName.class)
    ResponseEntity<Map<String, String>> duplicate(TopicService.DuplicateName e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "duplicate_topic", "detail", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_topic", "detail", e.getMessage()));
    }

    // ------------------------------------------------------------------ payloads

    record TopicRequest(
            @NotBlank @Size(max = TopicService.MAX_NAME) String name
    ) {}

    record TopicResponse(UUID id, String name, Instant createdAt, long documentCount) {
        static TopicResponse from(Topic t) {
            return new TopicResponse(t.id(), t.name(), t.createdAt(), t.documentCount());
        }
    }
}
