package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.notes.NoteService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.EventPublication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What happens to a document that cannot be indexed.
 *
 * <p>Spring Modulith leaves a publication untouched when its listener throws and applies
 * no retry limit of its own, so an unbounded resubmission policy retries a permanently
 * failing document forever. These tests pin both halves: that a failure really is
 * recorded rather than lost, and that the attempt bound stops retrying eventually.
 */
@SpringBootTest
@ActiveProfiles("local")
class IngestionRecoveryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IngestionRecovery recovery;

    /** Poisons indexing: every document fails to embed, permanently and identically. */
    @MockitoBean
    EmbeddingClient embeddings;

    @Test
    void aDocumentThatCannotBeIndexedLeavesItsWorkRecorded() {
        Mockito.when(embeddings.embed(Mockito.anyList()))
                .thenThrow(new IllegalStateException("embedding server is unreachable"));

        UUID owner = dev.rovernotes.TestAccounts.create(jdbc);
        var document = notes.create(owner, "poison", "A document that will not embed.");

        // The write itself must succeed — losing the note because indexing failed would
        // be a far worse outcome than an unsearchable note.
        assertThat(notes.get(owner, document.id()).content()).isNotBlank();

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(incompletePublications()).isPositive());

        // Left incomplete, so the work is recoverable, and counted, so a bound can apply.
        assertThat(attemptsRecorded()).isPositive();
        assertThat(chunksFor(document.id())).isZero();
    }

    @Test
    void retryingStopsAtTheConfiguredBound() {
        // The predicate is what the resubmission filter consults. Below the bound the
        // publication is offered again; at it, the document is left alone rather than
        // retried at every interval for the life of the deployment.
        assertThat(recovery.worthRetrying(publicationAttempted(1))).isTrue();
        assertThat(recovery.worthRetrying(publicationAttempted(4))).isTrue();
        assertThat(recovery.worthRetrying(publicationAttempted(5))).isFalse();
        assertThat(recovery.worthRetrying(publicationAttempted(50))).isFalse();
    }

    @Test
    void aBoundBelowOneIsRejected() {
        // Zero would mean never retrying anything, which silently disables recovery
        // rather than bounding it.
        assertThatThrownBy(() -> new IngestionRecovery(
                options -> { }, 0, Duration.ofSeconds(60), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRetryDelayDoublesPerAttemptAndIsCapped() {
        // 0s on the first retry, then 60s, 120s, 240s. At the committed bound of five
        // attempts the cap is never reached; it exists so raising the bound does not
        // silently produce hour-long gaps.
        assertThat(recovery.delayAfter(1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(recovery.delayAfter(2)).isEqualTo(Duration.ofSeconds(120));
        assertThat(recovery.delayAfter(3)).isEqualTo(Duration.ofSeconds(240));
        assertThat(recovery.delayAfter(30)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aLargeAttemptCountDoesNotOverflowIntoNoDelayAtAll() {
        // Doubling in a long overflows past 63 steps and lands negative, which reads as
        // "due immediately" — retrying hardest in exactly the case the cap exists for.
        assertThat(recovery.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
        assertThat(recovery.delayAfter(Integer.MAX_VALUE)).isPositive();
    }

    @Test
    void aFirstRetryIsNotDelayed() {
        // The common failure is transient — the embedding server restarting mid-ingest —
        // and making every document wait out a backoff it does not need would slow the
        // case that recovers on its own.
        assertThat(recovery.worthRetrying(publicationAttempted(1, null))).isTrue();
    }

    @Test
    void aPublicationTriedMomentsAgoIsNotTriedAgainYet() {
        // Without this the sweep retries a doomed document at full cadence: five attempts
        // in five minutes, each one a call to a service that is already failing.
        assertThat(recovery.worthRetrying(publicationAttempted(2, Instant.now()))).isFalse();
    }

    @Test
    void aPublicationPastItsBackoffIsTriedAgain() {
        assertThat(recovery.worthRetrying(
                publicationAttempted(2, Instant.now().minus(Duration.ofMinutes(5))))).isTrue();
    }

    @Test
    void theBoundOutranksTheBackoff() {
        // A spent publication is refused whether or not its delay has elapsed. Checking
        // the backoff first would keep offering it forever at a widening interval.
        assertThat(recovery.worthRetrying(
                publicationAttempted(5, Instant.now().minus(Duration.ofHours(9))))).isFalse();
    }

    @Test
    void aBackoffCapBelowTheBaseIsRejected() {
        // Not a shorter backoff but an inverted one: the first retry would wait longer
        // than the last.
        assertThatThrownBy(() -> new IngestionRecovery(
                options -> { }, 5, Duration.ofMinutes(10), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBackoffOfZeroIsRejected() {
        assertThatThrownBy(() -> new IngestionRecovery(
                options -> { }, 5, Duration.ZERO, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long incompletePublications() {
        return count("select count(*) from event_publication where completion_date is null");
    }

    private long attemptsRecorded() {
        return count("select coalesce(max(completion_attempts), 0) from event_publication");
    }

    private long chunksFor(UUID documentId) {
        return jdbc.sql("select count(*) from chunks where document_id = :id")
                .param("id", documentId).query(Long.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static EventPublication publicationAttempted(int attempts) {
        // Never resubmitted, so no backoff applies and the bound is what decides.
        return publicationAttempted(attempts, null);
    }

    private static EventPublication publicationAttempted(int attempts, Instant lastResubmission) {
        EventPublication publication = Mockito.mock(EventPublication.class);
        Mockito.when(publication.getCompletionAttempts()).thenReturn(attempts);
        Mockito.when(publication.getEvent()).thenReturn("poison");
        Mockito.when(publication.getPublicationDate()).thenReturn(Instant.EPOCH);
        Mockito.when(publication.getLastResubmissionDate()).thenReturn(lastResubmission);
        return publication;
    }
}
