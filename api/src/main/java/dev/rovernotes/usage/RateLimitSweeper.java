package dev.rovernotes.usage;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes the rate-limit rows that have stopped meaning anything.
 *
 * <p>{@link RateLimiter} writes one row per caller per bucket and never removes any. For
 * the buckets keyed on an account that is bounded by the number of accounts, which is a
 * number this system already stores. The unauthenticated bucket is keyed on the client
 * address, and that is bounded by nothing: every distinct address that ever reaches
 * registration leaves a row behind, whether or not it belongs to anyone.
 *
 * <p>Nothing is lost by removing them, and that is a property of the arithmetic rather
 * than an approximation. A bucket that has refilled to capacity answers the next request
 * exactly as no bucket at all does — an absent row is inserted with {@code capacity - 1},
 * and a full one resolves to {@code least(capacity, capacity + more) - 1}, the same
 * number. So a full row is disposable, and {@link RateLimiter#longestRefill()} is when
 * every bucket is certainly full.
 *
 * <p>The threshold is derived rather than configured for that reason. A separate setting
 * could be given a value shorter than a bucket takes to refill, which would hand a caller
 * back an allowance they had not earned — a limit quietly weakened by a housekeeping
 * knob. There is no value to get wrong here.
 */
@Component
class RateLimitSweeper {

    private static final Logger log = LoggerFactory.getLogger(RateLimitSweeper.class);

    private static final String SWEEP = """
            delete from rate_limits
             where refilled_at < now() - make_interval(secs => :seconds)
            """;

    private final JdbcClient jdbc;
    private final Duration disposableAfter;

    RateLimitSweeper(JdbcClient jdbc, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.disposableAfter = limiter.longestRefill();
    }

    /**
     * Far less often than a bucket refills, because nothing depends on the timing.
     *
     * <p>A row that outlives its usefulness costs one row until the next sweep; there is
     * no correctness or fairness question in how long that is, only how large the table
     * grows between passes. Sweeping every few seconds would spend a statement to reclaim
     * a handful of rows.
     *
     * <p>The initial delay matches, so a restart loop does not turn startup into a stream
     * of deletes.
     */
    @Scheduled(fixedDelayString = "${rover.rate-limit.sweep-interval:15m}",
            initialDelayString = "${rover.rate-limit.sweep-interval:15m}")
    void sweep() {
        int removed = jdbc.sql(SWEEP)
                .param("seconds", disposableAfter.toMillis() / 1000.0)
                .update();

        if (removed > 0) {
            log.debug("swept {} rate-limit rows idle longer than {}", removed, disposableAfter);
        }
    }

    /** The age at which a row is certainly full, so a test can assert on it. */
    Duration disposableAfter() {
        return disposableAfter;
    }
}
