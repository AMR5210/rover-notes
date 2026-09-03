package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
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
 * Topics against a real Postgres, because most of what a topic guarantees is a constraint.
 *
 * <p>Uniqueness per owner, the refusal to file a document under someone else's topic, and
 * a deleted topic leaving its documents behind are all enforced in the schema. Verifying
 * them anywhere but against the engine that enforces them would be testing the mock.
 */
@SpringBootTest
@ActiveProfiles("local")
class TopicServiceIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    TopicService topics;

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsAndListsTopics() {
        UUID owner = TestAccounts.create(jdbc);

        topics.create(owner, "Rock mechanics");
        topics.create(owner, "Machine learning");

        // Alphabetical, so a name keeps its position between visits.
        assertThat(topics.list(owner)).extracting(Topic::name)
                .containsExactly("Machine learning", "Rock mechanics");
    }

    @Test
    void trimsNamesBeforeStoringThem() {
        UUID owner = TestAccounts.create(jdbc);

        assertThat(topics.create(owner, "  Machine learning  ").name())
                .isEqualTo("Machine learning");
    }

    @Test
    void refusesTwoTopicsOfTheSameNameForOneOwner() {
        UUID owner = TestAccounts.create(jdbc);
        topics.create(owner, "Machine learning");

        assertThatThrownBy(() -> topics.create(owner, "Machine learning"))
                .isInstanceOf(TopicService.DuplicateName.class);
    }

    @Test
    void allowsTheSameNameForDifferentOwners() {
        UUID alice = TestAccounts.create(jdbc);
        UUID bob = TestAccounts.create(jdbc);

        topics.create(alice, "Machine learning");

        assertThat(topics.create(bob, "Machine learning").name()).isEqualTo("Machine learning");
    }

    @Test
    void refusesABlankName() {
        UUID owner = TestAccounts.create(jdbc);

        assertThatThrownBy(() -> topics.create(owner, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsTheDocumentsInEachTopic() {
        UUID owner = TestAccounts.create(jdbc);
        Topic ml = topics.create(owner, "Machine learning");
        topics.create(owner, "Rock mechanics");

        notes.create(owner, ml.id(), "Reranking", "late interaction scores per token");
        notes.create(owner, ml.id(), "Fusion", "RRF combines ranks");
        notes.create(owner, "Unfiled", "belongs nowhere");

        assertThat(topics.list(owner))
                .extracting(Topic::name, Topic::documentCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Machine learning", 2L),
                        org.assertj.core.groups.Tuple.tuple("Rock mechanics", 0L));
    }

    @Test
    void renamesATopicWithoutDisturbingItsDocuments() {
        UUID owner = TestAccounts.create(jdbc);
        Topic topic = topics.create(owner, "ML");
        Document note = notes.create(owner, topic.id(), "Reranking", "per-token scoring");

        Topic renamed = topics.rename(owner, topic.id(), "Machine learning");

        assertThat(renamed.name()).isEqualTo("Machine learning");
        assertThat(notes.get(owner, note.id()).topicId()).isEqualTo(topic.id());
    }

    @Test
    void deletingATopicKeepsItsDocumentsAndUnfilesThem() {
        UUID owner = TestAccounts.create(jdbc);
        Topic topic = topics.create(owner, "Machine learning");
        Document note = notes.create(owner, topic.id(), "Reranking", "per-token scoring");

        topics.delete(owner, topic.id());

        // The label goes; the reading stays. `on delete set null (topic_id)` in V8.
        assertThat(notes.get(owner, note.id()).topicId()).isNull();
        assertThat(notes.get(owner, note.id()).content()).isEqualTo("per-token scoring");
    }

    @Test
    void willNotReadRenameOrDeleteAnotherOwnersTopic() {
        UUID alice = TestAccounts.create(jdbc);
        UUID bob = TestAccounts.create(jdbc);
        Topic hers = topics.create(alice, "Machine learning");

        assertThat(topics.list(bob)).isEmpty();
        assertThatThrownBy(() -> topics.get(bob, hers.id()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> topics.rename(bob, hers.id(), "Bob's now"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> topics.delete(bob, hers.id()))
                .isInstanceOf(NoSuchElementException.class);

        assertThat(topics.get(alice, hers.id()).name()).isEqualTo("Machine learning");
    }

    @Test
    void willNotFileADocumentUnderAnotherOwnersTopic() {
        UUID alice = TestAccounts.create(jdbc);
        UUID bob = TestAccounts.create(jdbc);
        Topic hers = topics.create(alice, "Machine learning");

        assertThatThrownBy(() -> notes.create(bob, hers.id(), "Bob's note", "content"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
