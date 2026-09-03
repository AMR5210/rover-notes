package dev.rovernotes.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;

import dev.rovernotes.RequestLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A token bucket per caller, held in the database.
 *
 * <p>{@link SpendLimit} bounds what a caller's model calls cost. It says nothing about how
 * often they may ask, and nothing at all about the requests that spend no tokens —
 * searching, registering, requesting a password reset. This is the other bound: how fast,
 * rather than how much. Both live in this module because both are answers to "what has this
 * caller already used".
 *
 * <h2>Why the state is in Postgres</h2>
 *
 * <p>An in-process limiter is faster and multiplies the effective limit by the number of
 * tasks the service runs on. That is a limit which weakens as the service is scaled out
 * and looks correct the whole time, which is worse than not having one. The cost is a
 * single statement per request against a table with one row per active caller per bucket.
 *
 * <h2>The whole check is one statement</h2>
 *
 * <p>Reading the bucket, refilling it and taking a token have to be atomic, or two
 * concurrent requests both read the last token and both take it. An upsert with
 * {@code on conflict do update} does all three in one statement and holds its row lock
 * only for that statement, rather than across the request being guarded.
 *
 * <p>Refill is computed from the elapsed time on each read rather than by a timer: an idle
 * bucket costs nothing, and there is no scheduled job whose failure would silently stop
 * every limit refilling.
 */
@Service
public class RateLimiter implements RequestLimits {

    /**
     * Tokens available now: what was left, plus what has been earned since, capped.
     *
     * <p>Written once here and expanded into the statement below, so the refill rule has
     * one definition.
     */
    private static final String AVAILABLE = """
            least(:capacity,
                  r.tokens + extract(epoch from (now() - r.refilled_at)) * :perSecond)
            """;

    /**
     * Takes a token, and reports what is left.
     *
     * <p>A token is taken whether or not one was there, with the count floored at -1. That
     * floor is what makes the result readable: a caller who had a token ends at zero or
     * above and a caller who did not ends below zero, which are the two cases, and they
     * cannot be told apart from the remaining count alone if the take is skipped on
     * refusal.
     *
     * <p>The floor also bounds what a refused request costs. A caller who keeps asking
     * sits at -1 rather than sinking further, so hammering the endpoint delays their next
     * accepted request by at most one extra token's worth of time instead of extending the
     * refusal for as long as they keep trying.
     */
    private static final String TAKE = """
            insert into rate_limits as r (bucket, subject, tokens, refilled_at)
            values (:bucket, :subject, :capacity - 1, now())
            on conflict (bucket, subject) do update
               set tokens = greatest(%1$s - 1, -1),
                   refilled_at = now()
            returning tokens
            """.formatted(AVAILABLE);

    private final JdbcClient jdbc;

    /**
     * What each bucket allows.
     *
     * <p>Resolved once at startup rather than per request. A bucket name that is not here
     * falls back to the general allowance, which is the conservative direction: an
     * unrecognised name is a mistake in the filter, and answering it with no limit at all
     * would turn that mistake into an unbounded endpoint.
     */
    private final Map<String, Limit> limits;

    RateLimiter(JdbcClient jdbc,
                @Value("${rover.rate-limit.enabled:true}") boolean enabled,
                @Value("${rover.rate-limit.api-per-minute:120}") int api,
                @Value("${rover.rate-limit.ingest-per-minute:20}") int ingest,
                @Value("${rover.rate-limit.auth-per-minute:10}") int auth) {
        // An allowance of zero refuses every request and never refills, which is not a
        // stricter limit but a broken one — and it would make the sweeper's arithmetic
        // divide by zero. Refusing to start says so at the point the value was set.
        requireAtLeastOne("api-per-minute", api);
        requireAtLeastOne("ingest-per-minute", ingest);
        requireAtLeastOne("auth-per-minute", auth);

        this.jdbc = jdbc;
        this.limits = Map.of(
                RequestLimits.API, Limit.perMinute(enabled, api),
                RequestLimits.INGEST, Limit.perMinute(enabled, ingest),
                RequestLimits.AUTH, Limit.perMinute(enabled, auth));
    }

    private static void requireAtLeastOne(String property, int perMinute) {
        if (perMinute < 1) {
            throw new IllegalArgumentException(
                    "rover.rate-limit." + property + " must be at least 1, was " + perMinute
                            + "; set rover.rate-limit.enabled to false to turn limiting off");
        }
    }

    /**
     * How long the slowest bucket takes to refill from the floor to capacity.
     *
     * <p>What {@link RateLimitSweeper} needs, and derived here because it is a consequence
     * of the arithmetic above rather than a separate setting. A row that has refilled to
     * capacity answers the next request identically to no row at all: an absent row is
     * inserted with {@code capacity - 1}, and a full one resolves to
     * {@code least(capacity, capacity + more) - 1}, which is the same number. Once a bucket
     * is full it is therefore disposable, and this is when that is certainly true.
     *
     * <p>The worst case is a bucket at the floor of -1, so the distance to cover is
     * {@code capacity + 1} tokens. Rounded up, because the guarantee has to be that enough
     * time has passed and not that about enough has.
     */
    Duration longestRefill() {
        return limits.values().stream()
                .map(RateLimiter::timeToFill)
                .max(Duration::compareTo)
                .orElseThrow();
    }

    private static Duration timeToFill(Limit limit) {
        return Duration.ofMillis(
                (long) Math.ceil((limit.capacity() + 1) / limit.perSecond() * 1000));
    }

    @Override
    public Decision take(String bucket, String subject) {
        return take(bucket, subject,
                limits.getOrDefault(bucket, limits.get(RequestLimits.API)));
    }

    /**
     * The same take against an allowance given explicitly.
     *
     * <p>Package-private, and the form the tests use: the arithmetic is what they are
     * about, and expressing a case as "three a minute" is clearer than configuring a
     * property and then reasoning back to what it means.
     *
     * <p>Runs in its own transaction. The limit has to be recorded whatever the request it
     * guards goes on to do — a request that is refused later, or one that throws, has still
     * been made — and joining the caller's transaction would roll the take back with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Decision take(String bucket, String subject, Limit limit) {
        if (!limit.enabled()) {
            return Decision.allow();
        }

        BigDecimal remaining = jdbc.sql(TAKE)
                .param("bucket", bucket)
                .param("subject", subject)
                .param("capacity", limit.capacity())
                .param("perSecond", limit.perSecond())
                .query(BigDecimal.class)
                .single();

        if (remaining.signum() >= 0) {
            return Decision.allow();
        }
        return Decision.refuse(secondsUntilOneToken(remaining, limit));
    }

    /**
     * How long until the bucket holds a whole token again.
     *
     * <p>Rounded up, and never below one: a hint of zero seconds tells a client to retry
     * immediately, into the same refusal.
     */
    private static long secondsUntilOneToken(BigDecimal remaining, Limit limit) {
        BigDecimal needed = BigDecimal.ONE.subtract(remaining);
        long seconds = needed
                .divide(BigDecimal.valueOf(limit.perSecond()), 0, RoundingMode.CEILING)
                .longValue();
        return Math.max(seconds, 1L);
    }

    /**
     * One allowance.
     *
     * <p>Capacity and rate are separate because they answer different questions: capacity
     * is how much a caller who has been idle may spend at once, and the rate is what they
     * are held to after that.
     */
    record Limit(boolean enabled, int capacity, double perSecond) {

        /** An allowance expressed the way it is configured: so many requests a minute. */
        static Limit perMinute(boolean enabled, int perMinute) {
            return new Limit(enabled, perMinute, perMinute / 60.0);
        }
    }
}
