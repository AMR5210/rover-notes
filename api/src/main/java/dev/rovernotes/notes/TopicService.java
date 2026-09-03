package dev.rovernotes.notes;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, listing, renaming and removing topics.
 *
 * <p>Names are trimmed before they are stored and compared. Two topics whose names differ
 * only by surrounding space would be one name to a reader and two rows to the unique
 * constraint, so the whitespace is removed at the edge rather than being carried into a
 * comparison that has to remember it.
 */
@Service
public class TopicService {

    /** Long enough for a sentence, short enough to sit on one line beside a document. */
    static final int MAX_NAME = 80;

    private final TopicRepository topics;

    TopicService(TopicRepository topics) {
        this.topics = topics;
    }

    @Transactional(readOnly = true)
    public List<Topic> list(UUID ownerId) {
        return topics.listByOwner(ownerId);
    }

    @Transactional(readOnly = true)
    public Topic get(UUID ownerId, UUID id) {
        return topics.findByIdAndOwner(id, ownerId)
                .orElseThrow(() -> new NoSuchElementException("No topic " + id));
    }

    @Transactional
    public Topic create(UUID ownerId, String name) {
        String clean = clean(name);
        try {
            return topics.create(ownerId, clean);
        } catch (DuplicateKeyException e) {
            throw new DuplicateName(clean);
        }
    }

    @Transactional
    public Topic rename(UUID ownerId, UUID id, String name) {
        String clean = clean(name);
        try {
            if (!topics.rename(id, ownerId, clean)) {
                throw new NoSuchElementException("No topic " + id);
            }
        } catch (DuplicateKeyException e) {
            throw new DuplicateName(clean);
        }
        return get(ownerId, id);
    }

    /**
     * Removes a topic. The documents filed under it are kept and become unfiled.
     *
     * <p>Deleting the label does not delete the reading. Someone tidying up a topic they
     * no longer want is not asking to lose the documents in it, and a delete that took
     * them would be unrecoverable from the interface.
     */
    @Transactional
    public void delete(UUID ownerId, UUID id) {
        if (!topics.delete(id, ownerId)) {
            throw new NoSuchElementException("No topic " + id);
        }
    }

    /**
     * Checks a topic is one this owner has, before a document is filed under it.
     *
     * <p>V8's foreign key refuses the write regardless, which is what makes the rule
     * true. This exists so the refusal reaches the caller as "no such topic" rather than
     * as a constraint violation, which would arrive as a 500 describing a column name.
     */
    @Transactional(readOnly = true)
    public void requireOwned(UUID ownerId, UUID topicId) {
        if (topicId != null && topics.findByIdAndOwner(topicId, ownerId).isEmpty()) {
            throw new NoSuchElementException("No topic " + topicId);
        }
    }

    private static String clean(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a topic needs a name");
        }
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException(
                    "a topic name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }

    /** This owner already has a topic of that name. */
    public static class DuplicateName extends RuntimeException {

        public DuplicateName(String name) {
            super("there is already a topic called '" + name + "'");
        }
    }
}
