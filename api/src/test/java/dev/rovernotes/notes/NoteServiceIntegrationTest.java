package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import dev.rovernotes.TestAccounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs against a real Postgres with pgvector via Testcontainers.
 *
 * <p>The schema relies on {@code vector} columns, {@code tsvector} generated columns,
 * {@code pg_trgm}, partial indexes, and {@code SKIP LOCKED}. Testing against the same
 * engine used in production keeps these results meaningful, which is worth the extra
 * startup time over an embedded database.
 */
@SpringBootTest
@ActiveProfiles("local")
class NoteServiceIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    NoteService notes;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void createsAndReadsBackANote() {
        UUID owner = TestAccounts.create(jdbc);

        Document created = notes.create(owner, "Retrieval notes", "RRF fuses ranks, not scores.");

        assertThat(created.id()).isNotNull();
        assertThat(notes.get(owner, created.id()).content()).isEqualTo("RRF fuses ranks, not scores.");
        assertThat(notes.count(owner)).isEqualTo(1);
    }

    @Test
    void isolatesOwners() {
        UUID alice = TestAccounts.create(jdbc);
        UUID bob = TestAccounts.create(jdbc);

        notes.create(alice, "Alice's note", "private");

        assertThat(notes.count(bob)).isZero();
        assertThat(notes.list(bob, 50, 0)).isEmpty();
    }

    @Test
    void skipsRewriteWhenContentIsUnchanged() {
        UUID owner = TestAccounts.create(jdbc);
        Document created = notes.create(owner, "Title", "unchanged body");

        // Read back before comparing: Instant.now() carries nanosecond precision while
        // Postgres timestamptz stores microseconds, so the in-memory value returned by
        // create() and the stored value differ below the microsecond. Comparing two
        // persisted values keeps the assertion about behaviour rather than precision.
        Document persisted = notes.get(owner, created.id());

        Document updated = notes.update(owner, created.id(), "Title", "unchanged body");

        // Same hash and same updated_at: no write happened, so no re-indexing is queued.
        assertThat(updated.contentHash()).isEqualTo(persisted.contentHash());
        assertThat(updated.updatedAt()).isEqualTo(persisted.updatedAt());
    }

    @Test
    void rehashesWhenContentChanges() {
        UUID owner = TestAccounts.create(jdbc);
        Document created = notes.create(owner, "Title", "first body");

        Document updated = notes.update(owner, created.id(), "Title", "second body");

        assertThat(updated.contentHash()).isNotEqualTo(created.contentHash());
    }
}
