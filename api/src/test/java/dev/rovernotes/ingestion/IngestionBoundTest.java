package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.notes.NoteService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The retry bound, against the registry that actually counts.
 *
 * <p>{@code IngestionRecoveryTest} proves the filter decides correctly, but it hands the
 * filter publications built by hand. That leaves the question the filter depends on
 * unasked: whether a real publication reports a real attempt count. It is a fair question
 * — Spring Modulith ships two JDBC repositories, and the legacy one returns a hardcoded
 * attempt count of 1 because its schema has no column for it. Against that repository the
 * bound would never fire, every test above would still pass, and a permanently failing
 * document would be retried for the life of the deployment.
 *
 * <p>So this drives a document that cannot be indexed through the real scheduler and
 * watches the count in the table. The intervals are compressed to keep it quick; what is
 * being checked is that the mechanism engages, not how long it waits.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "rover.ingestion.max-index-attempts=3",
        "rover.ingestion.retry-interval=200ms",
        "rover.ingestion.retry-backoff=1ms",
        "rover.ingestion.max-retry-backoff=10ms",
})
class IngestionBoundTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    /** Poisons indexing: every document fails to embed, permanently and identically. */
    @MockitoBean
    EmbeddingClient embeddings;

    @Test
    void aDocumentThatCannotBeIndexedStopsBeingRetried() {
        UUID document = poisonedDocument();

        // Climbs to the bound...
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(attemptsFor(document)).isEqualTo(3));

        // ...and stays there. This is the assertion the mocked test cannot make: the
        // count comes from the registry, so it only holds if real publications carry a
        // real attempt count and the filter is reading it.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(attemptsFor(document)).isEqualTo(3));
    }

    @Test
    void aSpentPublicationSettlesAsFailedRatherThanCyclingForever() {
        // Below the bound a publication flips between FAILED and RESUBMITTED on every
        // sweep, so FAILED only means "given up on" once the attempts are spent. That
        // terminal state is what makes the row worth inspecting afterwards.
        UUID document = poisonedDocument();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(attemptsFor(document)).isEqualTo(3));

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(statusFor(document)).isEqualTo("FAILED"));
    }

    @Test
    void theFailedDocumentIsStillIdentifiableAfterwards() {
        // It stays in the registry rather than being deleted, because the useful question
        // afterwards is which document it was.
        UUID document = poisonedDocument();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(attemptsFor(document)).isEqualTo(3));

        assertThat(jdbc.sql("""
                        select serialized_event from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + document + "%")
                .query(String.class)
                .single()).contains(document.toString());
    }

    /** A document whose indexing will fail on every attempt, permanently. */
    private UUID poisonedDocument() {
        Mockito.when(embeddings.embed(Mockito.anyList()))
                .thenThrow(new IllegalStateException("embedding server is unreachable"));
        UUID owner = TestAccounts.create(jdbc);
        return notes.create(owner, "poison", "A document that will not embed. " + UUID.randomUUID())
                .id();
    }

    /**
     * Attempts recorded against one document's publication.
     *
     * <p>Per document rather than a maximum over the table: these tests share a database,
     * and a global count would let one test's assertion be satisfied by another test's
     * poison document.
     */
    private long attemptsFor(UUID documentId) {
        return jdbc.sql("""
                        select coalesce(max(completion_attempts), 0) from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + documentId + "%")
                .query(Long.class)
                .single();
    }

    private String statusFor(UUID documentId) {
        return jdbc.sql("""
                        select status from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + documentId + "%")
                .query(String.class)
                .single();
    }
}
