package dev.rovernotes.notes;

import java.util.UUID;

/**
 * Which slice of the library to list: all of it, one topic, or what has not been filed.
 *
 * <p>Three cases rather than a nullable topic id, because "no topic given" and "the
 * documents with no topic" are different requests that a null would collapse into one.
 * The first is what the library shows when it opens; the second is a filter someone
 * chose, and on a library that predates topics it selects everything.
 */
public record TopicFilter(UUID topicId, boolean unfiledOnly) {

    public static TopicFilter all() {
        return new TopicFilter(null, false);
    }

    public static TopicFilter unfiled() {
        return new TopicFilter(null, true);
    }

    public static TopicFilter of(UUID topicId) {
        return new TopicFilter(topicId, false);
    }
}
