package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The one claim the sequential tests cannot make: that the take is atomic.
 *
 * <p>{@link RateLimiter}'s design rests on it. Reading a bucket, refilling it and taking a
 * token happen in one statement precisely so that "two concurrent requests both read the
 * last token and both take it" cannot happen — that sentence is in the class's own
 * documentation, and until now nothing exercised it. Every existing case takes tokens one
 * after another, where a lost update has no opportunity to occur, so an implementation
 * that read and then wrote in two statements would pass all of them.
 *
 * <p>A limit that over-issues under load fails in the direction that has no symptom. The
 * service stays up, no request errors, and the allowance is quietly larger than it reads —
 * which is the same failure the class rejected an in-process limiter for.
 *
 * <h2>Why refill is switched off rather than left running</h2>
 *
 * <p>The allowance below is 200 tokens at a thousandth of a token per second, so the next
 * one is earned about seventeen minutes from now. That makes the expected count exactly
 * the capacity, with no dependence on how long the burst took: at the configured rates a
 * token arrives every second or so, and a burst that ran slowly on a loaded machine could
 * earn one and turn a correct result into a failure. The refill arithmetic has its own
 * tests, which age the row instead of waiting.
 */
@SpringBootTest
@ActiveProfiles("local")
class RateLimiterConcurrencyTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    RateLimiter limiter;

    @Autowired
    JdbcClient jdbc;

    private static final int CAPACITY = 200;

    /** Capacity as configured, refill effectively stopped for the length of the burst. */
    private static final RateLimiter.Limit FROZEN =
            new RateLimiter.Limit(true, CAPACITY, 0.001);

    private static final int THREADS = 16;
    private static final int TAKES_EACH = 40;

    /**
     * Fires every thread at one bucket at once and reports how many were allowed.
     *
     * <p>The barrier is what makes this a burst rather than a queue. Threads started in a
     * loop tend to finish their first take before the last has begun, which is the
     * sequential case again with extra machinery.
     */
    private int allowedInABurst(String subject) throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Callable<Void>> work = IntStream.range(0, THREADS)
                    .<Callable<Void>>mapToObj(t -> () -> {
                        start.await();
                        for (int i = 0; i < TAKES_EACH; i++) {
                            if (limiter.take("test", subject, FROZEN).allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                        return null;
                    })
                    .toList();

            for (Future<Void> finished : pool.invokeAll(work)) {
                finished.get();
            }
        }
        return allowed.get();
    }

    @Test
    void aBurstIsAllowedExactlyTheCapacityAndNoMore() throws Exception {
        // 640 requests arriving together against an allowance of 200. Read-then-write
        // would let two transactions see the same remaining count and both spend it, and
        // the result would be somewhere above 200 rather than at it.
        String subject = "subject-" + UUID.randomUUID();

        assertThat(allowedInABurst(subject)).isEqualTo(CAPACITY);
    }

    @Test
    void theFloorStillHoldsWhenTheRefusalsArriveTogether() throws Exception {
        // greatest(..., -1) bounds what a refused caller costs themselves: they sit at -1
        // however hard they try, rather than sinking with every attempt. Under a burst
        // that floor is applied 440 times over, and if it were subtraction without the
        // bound the bucket would be hundreds of tokens in debt — a caller who would then
        // wait hours for a limit that reads as a minute.
        String subject = "subject-" + UUID.randomUUID();
        allowedInABurst(subject);

        BigDecimal tokens = jdbc.sql(
                        "select tokens from rate_limits where bucket = 'test' and subject = :s")
                .param("s", subject)
                .query(BigDecimal.class)
                .single();

        assertThat(tokens).isCloseTo(BigDecimal.valueOf(-1),
                org.assertj.core.data.Offset.offset(BigDecimal.valueOf(0.01)));
    }

    @Test
    void callersInTheSameBurstDoNotSpendEachOthersAllowance() throws Exception {
        // The conflict target is (bucket, subject). If a burst from one caller could touch
        // another's row — or serialise them into one — the isolation this limit exists to
        // provide would hold only while traffic was sequential.
        String first = "subject-" + UUID.randomUUID();
        String second = "subject-" + UUID.randomUUID();

        assertThat(allowedInABurst(first)).isEqualTo(CAPACITY);
        assertThat(limiter.take("test", second, FROZEN).allowed()).isTrue();
    }

    @Test
    void everyRequestInTheBurstIsAccountedForOneWayOrTheOther() throws Exception {
        // Neither allowed nor refused is the third outcome that would matter: a take that
        // threw — on a deadlock, a serialisation failure, a connection the pool could not
        // give out — would be a request the limiter neither counted nor guarded, and
        // invokeAll would surface it here rather than in a log nobody reads.
        String subject = "subject-" + UUID.randomUUID();
        AtomicInteger decisions = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Callable<Void>> work = IntStream.range(0, THREADS)
                    .<Callable<Void>>mapToObj(t -> () -> {
                        start.await();
                        for (int i = 0; i < TAKES_EACH; i++) {
                            RequestLimits.Decision decision =
                                    limiter.take("test", subject, FROZEN);
                            assertThat(decision).isNotNull();
                            decisions.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> finished : pool.invokeAll(work)) {
                finished.get();
            }
        }

        assertThat(decisions.get()).isEqualTo(THREADS * TAKES_EACH);
    }
}
