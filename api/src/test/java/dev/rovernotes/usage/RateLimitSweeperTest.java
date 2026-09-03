package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rovernotes.RequestLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Reclaiming the rate-limit rows that no longer say anything.
 *
 * <p>One case carries the weight: that removing a full bucket and keeping one are the same
 * thing from the next caller's point of view. Everything else about this is housekeeping,
 * and housekeeping that returned allowance to a caller who had not earned it would be a
 * limit weakened by its own cleanup — the kind of fault that shows up as traffic getting
 * through rather than as anything failing.
 *
 * <p>The limit is switched on with a property. It is off in the {@code local} profile that
 * the rest of this class runs under, and a sweeper with nothing to sweep would pass every
 * assertion here by writing no rows at all.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "rover.rate-limit.enabled=true",
        "rover.rate-limit.api-per-minute=120",
        "rover.rate-limit.ingest-per-minute=20",
        "rover.rate-limit.auth-per-minute=10"})
class RateLimitSweeperTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    RateLimitSweeper sweeper;

    @Autowired
    RateLimiter limiter;

    @Autowired
    JdbcClient jdbc;

    private static String subject() {
        return "subject-" + java.util.UUID.randomUUID();
    }

    @BeforeEach
    void emptyTheTable() {
        jdbc.sql("delete from rate_limits").update();
    }

    /** Pretends the bucket was last touched this long ago. */
    private void aged(String subject, Duration idle) {
        jdbc.sql("""
                        update rate_limits
                           set refilled_at = now() - make_interval(secs => :seconds)
                         where subject = :subject
                        """)
                .param("seconds", idle.toMillis() / 1000.0)
                .param("subject", subject)
                .update();
    }

    private Optional<BigDecimal> tokens(String subject) {
        return jdbc.sql("select tokens from rate_limits where subject = :subject")
                .param("subject", subject)
                .query(BigDecimal.class)
                .optional();
    }

    /**
     * The property the whole thing rests on.
     *
     * <p>A caller whose bucket has refilled and been swept must be treated exactly as one
     * whose bucket refilled and was kept. If sweeping were even slightly generous it would
     * be a way to earn allowance by waiting, and nothing downstream would report it — the
     * requests simply get through.
     */
    @Test
    void aSweptBucketAndAKeptOneAnswerTheNextRequestIdentically() {
        String swept = subject();
        String kept = subject();
        for (String caller : new String[] {swept, kept}) {
            IntStream.range(0, 130).forEach(i -> limiter.take(RequestLimits.API, caller));
            aged(caller, sweeper.disposableAfter());
        }

        sweeper.sweep();
        assertThat(tokens(swept)).as("the full bucket was reclaimed").isEmpty();
        assertThat(tokens(kept)).as("aged the same, so this one went too").isEmpty();

        // Re-age only one of them, so the comparison is between a row that exists and one
        // that does not, at the same point in the refill.
        limiter.take(RequestLimits.API, kept);
        jdbc.sql("update rate_limits set tokens = 120 where subject = :s")
                .param("s", kept).update();

        assertThat(limiter.take(RequestLimits.API, swept).allowed()).isTrue();
        assertThat(limiter.take(RequestLimits.API, kept).allowed()).isTrue();
        assertThat(tokens(swept)).isPresent();
        assertThat(tokens(swept).orElseThrow().stripTrailingZeros())
                .as("an absent row and a full row leave the same count behind")
                .isEqualByComparingTo(tokens(kept).orElseThrow().stripTrailingZeros());
    }

    @Test
    void reclaimsABucketThatHasRefilledToCapacity() {
        String subject = subject();
        limiter.take(RequestLimits.AUTH, subject);
        aged(subject, sweeper.disposableAfter());

        sweeper.sweep();

        assertThat(tokens(subject)).isEmpty();
    }

    @Test
    void keepsABucketThatIsStillSpent() {
        // The failure that matters: an exhausted caller whose row is removed early starts
        // again from a full bucket, which is the limit not applying.
        String subject = subject();
        IntStream.range(0, 12).forEach(i -> limiter.take(RequestLimits.AUTH, subject));
        assertThat(limiter.take(RequestLimits.AUTH, subject).allowed()).isFalse();

        sweeper.sweep();

        assertThat(tokens(subject)).as("still spending down, so not disposable").isPresent();
        assertThat(limiter.take(RequestLimits.AUTH, subject).allowed()).isFalse();
    }

    @Test
    void keepsABucketThatIsOlderThanItsOwnRefillButNotTheSlowest() {
        // The threshold covers every bucket, so it is the slowest one that sets it. A
        // sweeper tuned to the fastest would reclaim the auth bucket while it was still
        // partly spent — the same fault as sweeping too early, reached by a different route.
        String subject = subject();
        IntStream.range(0, 12).forEach(i -> limiter.take(RequestLimits.AUTH, subject));
        aged(subject, Duration.ofSeconds(61));

        sweeper.sweep();

        assertThat(tokens(subject)).isPresent();
    }

    /**
     * The threshold is the slowest bucket's, worked out rather than written down.
     *
     * <p>At ten a minute the auth bucket earns a token every six seconds and, from the
     * floor of -1, needs eleven of them: 66 seconds. The API bucket is quicker in wall time
     * despite holding more (121 tokens at two a second, 60.5s), which is why the answer is
     * not simply the largest capacity.
     */
    @Test
    void takesItsThresholdFromTheSlowestBucketRatherThanTheLargest() {
        assertThat(limiter.longestRefill()).isEqualTo(Duration.ofSeconds(66));
        assertThat(sweeper.disposableAfter()).isEqualTo(limiter.longestRefill());
    }

    @Test
    void leavesEveryOtherCallerAlone() {
        String idle = subject();
        String active = subject();
        limiter.take(RequestLimits.API, idle);
        limiter.take(RequestLimits.API, active);
        aged(idle, sweeper.disposableAfter());

        sweeper.sweep();

        assertThat(tokens(idle)).isEmpty();
        assertThat(tokens(active)).as("touched just now, so not idle").isPresent();
    }
}
