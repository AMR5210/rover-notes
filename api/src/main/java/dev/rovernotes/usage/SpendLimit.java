package dev.rovernotes.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refuses a model call for an owner who has spent past their cap.
 *
 * <p>The cap is enforced before the request rather than after, because a refusal is only
 * useful while it can still prevent the spend. It bounds what has already been spent, not
 * what the next request will cost: that is not known until the model has answered, so a
 * single request can carry an owner past the cap and the one after it is refused. The
 * alternative is charging an estimate and reconciling later, which trades a bounded
 * overshoot for a permanently approximate ledger.
 *
 * <p>The window rolls rather than resetting at a fixed hour. A calendar reset concentrates
 * every capped user's retry at the same instant, and gives an owner who spends at 23:00 a
 * fresh allowance an hour later.
 *
 * <p>The default cap is generous against measured use: an answer over this corpus costs
 * about $0.015, so $5.00 a day is roughly three hundred of them. It exists to bound a loop
 * that has gone wrong rather than to ration ordinary use. Setting it to zero disables the
 * check entirely.
 */
@Service
public class SpendLimit {

    /**
     * Spend inside the window, and the point at which enough of it ages out.
     *
     * <p>{@code shed} is the running total from oldest to newest, so the first row whose
     * running total covers the excess is the one whose expiry brings the owner back under
     * the cap. Reporting when that happens is more useful than a fixed backoff, which is
     * either longer than necessary or wrong.
     */
    private static final String SPEND = """
            select coalesce(sum(cost_usd), 0)
            from llm_usage
            where owner_id = :owner
              and created_at > now() - make_interval(secs => :seconds)
            """;

    private static final String RELEASES_AT = """
            with charged as (
                select created_at,
                       sum(coalesce(cost_usd, 0)) over (order by created_at, id) as shed
                from llm_usage
                where owner_id = :owner
                  and created_at > now() - make_interval(secs => :seconds)
            )
            select ceil(extract(epoch from
                       (min(created_at) + make_interval(secs => :seconds) - now())))
            from charged
            where shed >= :excess
            """;

    private final JdbcClient jdbc;
    private final BigDecimal cap;
    private final Duration window;

    SpendLimit(JdbcClient jdbc,
               @Value("${rover.usage.cap-usd}") BigDecimal cap,
               @Value("${rover.usage.window}") Duration window) {
        this.jdbc = jdbc;
        this.cap = cap;
        this.window = window;
    }

    /**
     * Throws when the owner has already spent past the cap inside the window.
     *
     * @throws SpendLimitExceeded carrying what was spent, the cap, and how long until
     *                            enough of that spend ages out
     */
    @Transactional(readOnly = true)
    public void check(UUID ownerId) {
        if (cap.signum() <= 0) {
            return;
        }

        long seconds = window.toSeconds();
        BigDecimal spent = jdbc.sql(SPEND)
                .param("owner", ownerId)
                .param("seconds", seconds)
                .query(BigDecimal.class)
                .single();

        if (spent.compareTo(cap) < 0) {
            return;
        }

        throw new SpendLimitExceeded(spent, cap, retryAfterSeconds(ownerId, spent, seconds));
    }

    /** Seconds until enough of the window's spend expires to bring the owner under the cap. */
    private long retryAfterSeconds(UUID ownerId, BigDecimal spent, long seconds) {
        BigDecimal excess = spent.subtract(cap);

        Optional<BigDecimal> releases = jdbc.sql(RELEASES_AT)
                .param("owner", ownerId)
                .param("seconds", seconds)
                .param("excess", excess)
                .query(BigDecimal.class)
                .optional();

        // A row is always found when spend exceeds the cap, since the running total
        // reaches the whole window's spend. The floor of one second keeps a hint that has
        // just expired from reading as "retry immediately" to a client that would then
        // retry into the same refusal.
        return releases.map(BigDecimal::longValue).map(value -> Math.max(value, 1L)).orElse(1L);
    }

    /** The configured cap, so a caller can report it without reaching into configuration. */
    public BigDecimal cap() {
        return cap;
    }

    /**
     * The window the cap is enforced over.
     *
     * <p>Exposed rather than left to a second reader of the same property. Anything
     * reporting spend has to report it over this window, or a caller is refused at a
     * figure they were never shown; taking it from here makes that structural instead of
     * dependent on two injection points naming the same key.
     */
    public Duration window() {
        return window;
    }
}
