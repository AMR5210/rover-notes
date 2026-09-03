package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import dev.rovernotes.RequestLimits;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bucket arithmetic, against a real database.
 *
 * <p>All of it lives in one SQL statement — the refill, the cap, the take and the floor —
 * so there is nothing to test in Java and nothing a mocked {@code JdbcClient} would
 * exercise. Against Postgres the assertions are on the behaviour a caller sees.
 *
 * <p>Refill is tested by moving the row's timestamp backwards rather than by waiting.
 * Sleeping for the real interval would make this the slowest test in the suite and would
 * still only cover one elapsed time; setting {@code refilled_at} covers whichever elapsed
 * time the case needs, and it is the same arithmetic either way because the statement
 * reads the column rather than a clock it was handed.
 */
@SpringBootTest
@ActiveProfiles("local")
class RateLimiterTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    RateLimiter limiter;

    @Autowired
    JdbcClient jdbc;

    /** A caller nobody else in this class shares, so the cases do not interfere. */
    private static String subject() {
        return "subject-" + java.util.UUID.randomUUID();
    }

    private static final RateLimiter.Limit THREE_A_MINUTE = RateLimiter.Limit.perMinute(true, 3);

    /** Pretends the bucket was last touched this many seconds ago. */
    private void aged(String subject, int seconds) {
        jdbc.sql("""
                        update rate_limits
                           set refilled_at = now() - make_interval(secs => :seconds)
                         where bucket = 'test' and subject = :subject
                        """)
                .param("seconds", seconds)
                .param("subject", subject)
                .update();
    }

    @Test
    void allowsExactlyTheCapacityBeforeRefusing() {
        String subject = subject();

        var decisions = IntStream.range(0, 4)
                .mapToObj(i -> limiter.take("test", subject, THREE_A_MINUTE))
                .toList();

        assertThat(decisions).extracting(RequestLimits.Decision::allowed)
                .containsExactly(true, true, true, false);
    }

    @Test
    void reportsHowLongUntilTheNextRequestWouldBeAllowed() {
        String subject = subject();
        IntStream.range(0, 4).forEach(i -> limiter.take("test", subject, THREE_A_MINUTE));

        RateLimiter.Decision refused = limiter.take("test", subject, THREE_A_MINUTE);

        // Three a minute is a token every twenty seconds, and a refused caller sits at the
        // floor of -1, so a whole token is two refills away. A hint the caller can act on
        // rather than a fixed backoff that is either too long or wrong.
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(40);
    }

    @Test
    void refillsWithTheTimeThatHasPassed() {
        String subject = subject();
        IntStream.range(0, 4).forEach(i -> limiter.take("test", subject, THREE_A_MINUTE));
        assertThat(limiter.take("test", subject, THREE_A_MINUTE).allowed()).isFalse();

        aged(subject, 40);

        assertThat(limiter.take("test", subject, THREE_A_MINUTE).allowed()).isTrue();
    }

    @Test
    void doesNotRefillPastCapacity() {
        String subject = subject();
        limiter.take("test", subject, THREE_A_MINUTE);

        // An hour of refill against a bucket that holds three. Without the cap, a caller
        // who was idle overnight would arrive with an unbounded burst, which is the one
        // thing a limit exists to prevent.
        aged(subject, 3600);

        var decisions = IntStream.range(0, 4)
                .mapToObj(i -> limiter.take("test", subject, THREE_A_MINUTE))
                .toList();
        assertThat(decisions).extracting(RequestLimits.Decision::allowed)
                .containsExactly(true, true, true, false);
    }

    @Test
    void keepsARefusedCallerAtTheFloorHoweverHardTheyTry() {
        // Without the floor, a client retrying in a loop drives its own bucket further
        // negative on every attempt and extends its refusal for as long as it keeps
        // trying — a limit whose duration depends on the client's impatience.
        String subject = subject();
        IntStream.range(0, 50).forEach(i -> limiter.take("test", subject, THREE_A_MINUTE));

        aged(subject, 40);

        assertThat(limiter.take("test", subject, THREE_A_MINUTE).allowed()).isTrue();
    }

    @Test
    void countsEachCallerSeparately() {
        String exhausted = subject();
        IntStream.range(0, 4).forEach(i -> limiter.take("test", exhausted, THREE_A_MINUTE));

        assertThat(limiter.take("test", subject(), THREE_A_MINUTE).allowed()).isTrue();
    }

    @Test
    void countsEachBucketSeparately() {
        // A caller who has spent their writes for the minute can still search. Sharing one
        // bucket would let the cheaper request be denied by the more expensive one.
        String subject = subject();
        IntStream.range(0, 4).forEach(i -> limiter.take("test", subject, THREE_A_MINUTE));

        assertThat(limiter.take("other", subject, THREE_A_MINUTE).allowed()).isTrue();
    }

    @Test
    void writesNothingWhenTheLimitIsSwitchedOff() {
        String subject = subject();
        RateLimiter.Limit off = RateLimiter.Limit.perMinute(false, 3);

        IntStream.range(0, 10).forEach(i ->
                assertThat(limiter.take("test", subject, off).allowed()).isTrue());

        // Not merely allowed: no row exists, so switching the limit on later starts from a
        // full bucket rather than from a count nobody was enforcing.
        assertThat(jdbc.sql("select count(*) from rate_limits where subject = :subject")
                .param("subject", subject)
                .query(Integer.class)
                .single())
                .isZero();
    }
}
