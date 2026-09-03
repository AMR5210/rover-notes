package dev.rovernotes.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes how much indexing work is outstanding.
 *
 * <p>A write returns before its text is searchable, so "the document exists" and "the
 * document can be found" are different states with no signal between them. This gauge is
 * that signal: the number of event publications the ingestion listener has not completed.
 * Zero means every write has been indexed.
 *
 * <p>Two things depend on it. Operationally it is the queue-depth measurement that
 * distinguishes a slow indexer from a stuck one — a backlog that grows monotonically is
 * the symptom of a document failing on every attempt. For the eval harness it is the
 * difference between a deterministic measurement and a race: the harness previously
 * waited a fixed three seconds after seeding before scoring, which is a guess about how
 * long embedding takes on hardware it does not know, and on a slower machine it scores a
 * half-built index and reports the result as a retrieval number.
 */
@Component
class IngestionMetrics {

    private final JdbcClient jdbc;

    /**
     * Chunks whose embedding this indexer computed, and chunks that reused one.
     *
     * <p>The pair is what makes idempotency checkable rather than asserted. Re-importing
     * an unchanged document should move only the second counter, and editing one
     * paragraph of a long document should move the first by one — both are claims about a
     * number, and neither is visible from the backlog gauge or from the log line.
     *
     * <p>One meter with an {@code outcome} tag rather than two, so the ratio is a query
     * on a single series.
     */
    private final Counter embedded;
    private final Counter reused;

    IngestionMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("rover.ingestion.backlog", this, IngestionMetrics::backlog);
        registry.gauge("rover.ingestion.failed", this, IngestionMetrics::failed);
        this.embedded = chunkCounter(registry, "embedded");
        this.reused = chunkCounter(registry, "reused");
    }

    private static Counter chunkCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("rover.ingestion.chunks")
                .description("Chunks written during indexing, by whether they were embedded")
                .tag("outcome", outcome)
                .register(registry);
    }

    /** Records what one indexing pass computed and what it kept. */
    void chunksIndexed(int embeddedCount, int reusedCount) {
        embedded.increment(embeddedCount);
        reused.increment(reusedCount);
    }

    /**
     * Publications with no completion date, which is what the registry replays on
     * restart.
     *
     * <p>Read on scrape rather than tracked in memory, so a restart does not reset it and
     * a second instance does not report only its own share. The query is a count over an
     * indexed column on a table that is empty in the steady state.
     */
    private double backlog() {
        return count("select count(*) from event_publication where completion_date is null");
    }

    /**
     * Publications whose listener threw.
     *
     * <p>Separate from the backlog because they mean different things. Backlog is depth
     * and is expected to be non-zero while writes are being indexed. This one is not: a
     * failure that clears itself on retry is invisible here by the time anyone looks, so
     * a sustained non-zero value is a document that will not index, and the count keeps
     * rising once {@code max-index-attempts} stops the retries.
     */
    private double failed() {
        return count("select count(*) from event_publication where status = 'FAILED'");
    }

    private double count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0d : value;
    }
}
