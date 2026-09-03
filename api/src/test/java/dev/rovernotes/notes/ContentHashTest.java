package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Content hashing is the basis of ingestion idempotency, so its properties are worth
 * pinning down: identical input must always produce identical output (or unchanged
 * documents get re-embedded), and different input must not collide (or changed
 * documents get silently skipped).
 */
class ContentHashTest {

    @Test
    void isStableAcrossInvocations() {
        assertThat(ContentHash.of("retrieval is a two-stage problem"))
                .isEqualTo(ContentHash.of("retrieval is a two-stage problem"));
    }

    @Test
    void distinguishesDifferentContent() {
        assertThat(ContentHash.of("dense retrieval"))
                .isNotEqualTo(ContentHash.of("sparse retrieval"));
    }

    @Test
    void isSensitiveToWhitespaceAndCase() {
        assertThat(ContentHash.of("Notes")).isNotEqualTo(ContentHash.of("notes"));
        assertThat(ContentHash.of("a b")).isNotEqualTo(ContentHash.of("a  b"));
    }

    @Test
    void treatsNullAsEmpty() {
        assertThat(ContentHash.of(null)).isEqualTo(ContentHash.of(""));
    }

    @Test
    void producesHexEncodedSha256() {
        assertThat(ContentHash.of("anything")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void documentDetectsUnchangedContent() {
        Document doc = Document.newNote(java.util.UUID.randomUUID(), "Title", "body text");

        assertThat(doc.contentUnchanged("body text")).isTrue();
        assertThat(doc.contentUnchanged("body text ")).isFalse();
    }
}
