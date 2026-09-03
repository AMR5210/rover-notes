package dev.rovernotes.usage;

import java.util.Arrays;
import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the rate limit costs the request it guards.
 *
 * <p>The limiter counts in Postgres rather than in process, which buys a limit that does
 * not weaken as the service is scaled out and costs a statement per request. The
 * documentation quotes that cost, so it has to be reproducible by whoever reads it —
 * this began as a throwaway probe, which made the figure real but unrepeatable.
 *
 * <p>Tagged rather than left in the suite: 2,000 database round trips on every build, for
 * a number no assertion depends on, is a cost the suite should not carry. Run it with
 * {@code ./gradlew benchmark}.
 *
 * <p>Asserts nothing about the timing. A latency threshold in a suite is a test that fails
 * on a loaded machine rather than on a regression; the figure belongs in the documentation
 * beside the conditions it was taken under.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("benchmark")
class RateLimiterCostBenchmark {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    RateLimiter limiter;

    private static final int WARMUP = 200;
    private static final int SAMPLES = 2_000;

    @Test
    void takeCost() {
        // Capacity high enough and refill slow enough that no sample is refused: what is
        // being timed is the statement, and a refusal takes a different path through it.
        var limit = new RateLimiter.Limit(true, 1_000_000, 0.001);
        String subject = "benchmark-" + UUID.randomUUID();

        for (int i = 0; i < WARMUP; i++) {
            limiter.take("test", subject, limit);
        }

        long[] ns = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            limiter.take("test", subject, limit);
            ns[i] = System.nanoTime() - started;
        }
        Arrays.sort(ns);

        System.out.printf("%nrate-limit take, %d samples after %d warm-up:%n", SAMPLES, WARMUP);
        System.out.printf("  median %.3f ms   p95 %.3f ms   p99 %.3f ms   max %.3f ms%n%n",
                ns[SAMPLES / 2] / 1e6, ns[(int) (SAMPLES * 0.95)] / 1e6,
                ns[(int) (SAMPLES * 0.99)] / 1e6, ns[SAMPLES - 1] / 1e6);
    }
}
