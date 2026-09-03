package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Filing documents under topics, and listing the library by one.
 *
 * <p>The filter has three cases and two of them are easy to confuse: no topic asked for
 * returns everything, while the unfiled filter returns only what has no topic. On a
 * library written before topics existed those two return the same rows, which is exactly
 * why they are asserted apart here.
 */
@SpringBootTest
@ActiveProfiles("local")
class NoteTopicIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    NoteService notes;

    @Autowired
    TopicService topics;

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsANoteWithoutATopic() {
        UUID owner = TestAccounts.create(jdbc);

        assertThat(notes.create(owner, "Unfiled", "no topic").topicId()).isNull();
    }

    @Test
    void createsANoteInATopic() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");

        Document created = notes.create(owner, ml.id(), "Reranking", "per-token scoring");

        assertThat(created.topicId()).isEqualTo(ml.id());
        assertThat(notes.get(owner, created.id()).topicId()).isEqualTo(ml.id());
    }

    @Test
    void listsByTopicUnfiledAndEverything() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        Topic rock = topics.create(owner, "Rock mechanics");

        notes.create(owner, ml.id(), "Reranking", "per-token scoring");
        notes.create(owner, rock.id(), "Shear strength", "joint roughness coefficient");
        notes.create(owner, "Unfiled", "belongs nowhere yet");

        assertThat(notes.list(owner, TopicFilter.all(), 50, 0)).hasSize(3);
        assertThat(notes.count(owner, TopicFilter.all())).isEqualTo(3);

        assertThat(notes.list(owner, TopicFilter.of(ml.id()), 50, 0))
                .extracting(Document::title).containsExactly("Reranking");
        assertThat(notes.count(owner, TopicFilter.of(ml.id()))).isEqualTo(1);

        assertThat(notes.list(owner, TopicFilter.unfiled(), 50, 0))
                .extracting(Document::title).containsExactly("Unfiled");
        assertThat(notes.count(owner, TopicFilter.unfiled())).isEqualTo(1);
    }

    @Test
    void movesANoteBetweenTopics() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        Topic rock = topics.create(owner, "Rock mechanics");
        Document note = notes.create(owner, ml.id(), "Reranking", "per-token scoring");

        notes.update(owner, note.id(), "Reranking", "per-token scoring", rock.id());

        assertThat(notes.get(owner, note.id()).topicId()).isEqualTo(rock.id());
        assertThat(notes.count(owner, TopicFilter.of(ml.id()))).isZero();
    }

    @Test
    void unfilesANoteByMovingItToNoTopic() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        Document note = notes.create(owner, ml.id(), "Reranking", "per-token scoring");

        notes.update(owner, note.id(), "Reranking", "per-token scoring", null);

        assertThat(notes.get(owner, note.id()).topicId()).isNull();
    }

    @Test
    void changingOnlyTheTopicLeavesTheTextAndItsTimestampAlone() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        Document note = notes.create(owner, "Reranking", "per-token scoring");
        Document before = notes.get(owner, note.id());

        notes.update(owner, note.id(), "Reranking", "per-token scoring", ml.id());

        Document after = notes.get(owner, note.id());
        assertThat(after.topicId()).isEqualTo(ml.id());
        assertThat(after.content()).isEqualTo("per-token scoring");
        // A move re-embeds nothing, so the content hash and updatedAt do not move either.
        assertThat(after.contentHash()).isEqualTo(before.contentHash());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
    }

    @Test
    void keepsTheTopicWhenTheOlderUpdateSignatureIsUsed() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        Document note = notes.create(owner, ml.id(), "Reranking", "per-token scoring");

        notes.update(owner, note.id(), "Reranking", "now with a second sentence");

        assertThat(notes.get(owner, note.id()).topicId()).isEqualTo(ml.id());
    }

    @Test
    void doesNotLeakAnotherOwnersDocumentsThroughATopicFilter() {
        UUID alice = TestAccounts.create(jdbc);
        UUID bob = TestAccounts.create(jdbc);
        Topic hers = topics.create(alice, "Machine learning");
        notes.create(alice, hers.id(), "Reranking", "per-token scoring");

        assertThat(notes.list(bob, TopicFilter.of(hers.id()), 50, 0)).isEmpty();
        assertThat(notes.count(bob, TopicFilter.of(hers.id()))).isZero();
    }
}
