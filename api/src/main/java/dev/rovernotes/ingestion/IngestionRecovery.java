package dev.rovernotes.ingestion;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries indexing work that failed, a bounded number of times and at a widening interval.
 *
 * <p>Spring Modulith leaves a publication untouched when its listener throws, so the work
 * is not lost — and applies no retry limit of its own. The reference documentation is
 * explicit that resubmission is the application's decision, offered through
 * {@link FailedEventPublications#resubmit(ResubmissionOptions)} with a filter over the
 * publication.
 *
 * <p>The setting this replaces was
 * {@code republish-outstanding-events-on-restart}, which resubmits every incomplete
 * publication each time the application starts, with no notion of how often one has
 * already been tried. A document that fails for a transient reason — the embedding
 * server restarting mid-ingest — is recovered by that, which is why it was on. A document
 * that fails for a permanent reason is retried by it forever, which is why it is now off:
 * the two cases are indistinguishable to a mechanism that cannot count.
 *
 * <h2>The bound</h2>
 *
 * <p>{@code completionAttempts} is what distinguishes them. It is incremented when a
 * publication moves to processing, so it survives a crash during the listener, and past
 * {@code max-index-attempts} the publication is left alone. It stays in the registry as
 * {@code FAILED} rather than being deleted, because the useful question afterwards is
 * which document it was.
 *
 * <h2>The interval</h2>
 *
 * <p>The bound alone still retries a doomed document at full cadence until it is spent.
 * With a sweep every minute and a document that cannot be embedded, that is five attempts
 * in five minutes, each one a call to a service that is already failing, and the retries
 * arrive fastest exactly when something is broken. The delay now doubles per attempt from
 * {@code retry-backoff}, capped at {@code max-retry-backoff}, so a transient failure is
 * still recovered quickly and a persistent one stops adding load to whatever it is
 * failing against.
 *
 * <p>The sweep interval is a floor on that, not a competitor: a publication is offered
 * for resubmission at most once per sweep, and this decides whether it is due. The
 * effective delay is therefore the larger of the two.
 *
 * <h2>What Modulith already guarantees, and is not reimplemented here</h2>
 *
 * <p>Two instances sweeping at once do not process the same publication twice. The JDBC
 * repository moves a publication to {@code PROCESSING} with a conditional update —
 * {@code set status = ? where id = ? and status != ?} — so the second instance's update
 * matches no row and it does not proceed. That is a compare-and-set rather than the
 * {@code select ... for update skip locked} this project's roadmap named, and it settles
 * the same question without holding a row lock across the listener.
 */
@Component
class IngestionRecovery {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);

    private final FailedEventPublications failed;
    private final int maxAttempts;
    private final Duration backoff;
    private final Duration maxBackoff;

    IngestionRecovery(FailedEventPublications failed,
                      @Value("${rover.ingestion.max-index-attempts}") int maxAttempts,
                      @Value("${rover.ingestion.retry-backoff:60s}") Duration backoff,
                      @Value("${rover.ingestion.max-retry-backoff:30m}") Duration maxBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-index-attempts must be at least 1");
        }
        if (backoff.isNegative() || backoff.isZero()) {
            throw new IllegalArgumentException("retry-backoff must be positive, was " + backoff);
        }
        if (maxBackoff.compareTo(backoff) < 0) {
            // A cap below the base would make the first retry wait longer than the last,
            // which is not a shorter backoff but an inverted one.
            throw new IllegalArgumentException(
                    "max-retry-backoff must be at least retry-backoff, was " + maxBackoff);
        }
        this.failed = failed;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.maxBackoff = maxBackoff;
    }

    @Scheduled(fixedDelayString = "${rover.ingestion.retry-interval}",
            initialDelayString = "${rover.ingestion.retry-interval}")
    void resubmitFailed() {
        failed.resubmit(ResubmissionOptions.defaults().withFilter(this::worthRetrying));
    }

    /**
     * Whether a failed publication should be resubmitted on this sweep.
     *
     * <p>Logging the ones that are spent is the only place a permanently failing document
     * becomes visible by name; everywhere else it is one more unit of an indexing backlog
     * that never reaches zero.
     */
    boolean worthRetrying(EventPublication publication) {
        int attempts = publication.getCompletionAttempts();

        if (attempts >= maxAttempts) {
            log.warn("giving up on {} after {} attempts; it stays in the registry for inspection",
                    publication.getEvent(), attempts);
            return false;
        }

        return dueFor(publication, attempts);
    }

    /**
     * Whether enough time has passed since this publication was last tried.
     *
     * <p>A null last-resubmission date is a publication that has failed but never been
     * resubmitted, which is the first retry and is not delayed: the common failure is
     * transient, and making every document wait out a backoff it does not need would
     * slow the case that recovers on its own.
     */
    private boolean dueFor(EventPublication publication, int attempts) {
        Instant last = publication.getLastResubmissionDate();
        if (last == null) {
            return true;
        }
        return !Instant.now().isBefore(last.plus(delayAfter(attempts)));
    }

    /**
     * How long to wait after a given number of attempts.
     *
     * <p>Doubling from the base, capped. The exponent is computed in {@code long} and
     * bounded before it is used, so a publication that somehow reports a large attempt
     * count produces the cap rather than an overflowed negative duration — which would
     * read as "due immediately" and retry hardest in the case the cap exists to calm.
     */
    Duration delayAfter(int attempts) {
        int steps = Math.max(attempts - 1, 0);
        if (steps >= 32) {
            return maxBackoff;
        }
        Duration scaled = backoff.multipliedBy(1L << steps);
        return scaled.compareTo(maxBackoff) > 0 ? maxBackoff : scaled;
    }
}
