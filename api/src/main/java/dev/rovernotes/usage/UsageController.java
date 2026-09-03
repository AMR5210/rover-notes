package dev.rovernotes.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import dev.rovernotes.CurrentOwner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an owner has spent, and how much of their cap is left.
 *
 * <p>A cap that refuses a request without showing where the caller stands is only half a
 * control: the first thing anyone learns about it is that they have hit it. This reports
 * the same window the cap is enforced over, so the number here and the number in a refusal
 * are the same number.
 *
 * <p>Scoped to the caller's owner, using the same resolution as the rest of the API. There
 * is deliberately no way to ask about somebody else's spend.
 */
@RestController
@RequestMapping("/api/usage")
class UsageController {

    /**
     * Spend inside the cap's window, which is what the limit is enforced against.
     *
     * <p>Rows with no cost are still counted as calls. A model the registry does not price
     * produces one, and reporting the call while leaving the cost out is more honest than
     * dropping it from both.
     */
    private static final String WINDOW = """
            select count(*)                        as calls,
                   coalesce(sum(cost_usd), 0)      as cost,
                   coalesce(sum(input_tokens), 0)  as input_tokens,
                   coalesce(sum(output_tokens), 0) as output_tokens
            from llm_usage
            where owner_id = :owner
              and created_at > now() - make_interval(secs => :seconds)
            """;

    private static final String BY_MODEL = """
            select model_id,
                   count(*)                        as calls,
                   coalesce(sum(cost_usd), 0)      as cost,
                   coalesce(sum(input_tokens), 0)  as input_tokens,
                   coalesce(sum(output_tokens), 0) as output_tokens
            from llm_usage
            where owner_id = :owner
              and created_at > now() - make_interval(secs => :seconds)
            group by model_id
            order by cost desc
            """;

    /**
     * A week of daily totals, for context the window alone does not give.
     *
     * <p>Days with no spend are absent rather than reported as zero. A caller drawing this
     * knows the range it asked for and can fill the gaps; inventing rows here would mean
     * inventing a calendar the database has no opinion about.
     */
    private static final String DAILY = """
            select date_trunc('day', created_at)::date as day,
                   count(*)                            as calls,
                   coalesce(sum(cost_usd), 0)          as cost
            from llm_usage
            where owner_id = :owner
              and created_at > now() - interval '7 days'
            group by day
            order by day
            """;

    private final JdbcClient jdbc;
    private final CurrentOwner owner;
    private final SpendLimit limit;

    UsageController(JdbcClient jdbc, CurrentOwner owner, SpendLimit limit) {
        this.jdbc = jdbc;
        this.owner = owner;
        this.limit = limit;
    }

    @GetMapping
    UsageSummary summary() {
        // Taken from the limit rather than read from configuration a second time, so the
        // window reported here cannot drift from the one a refusal is based on.
        Duration window = limit.window();
        long seconds = window.toSeconds();

        Totals totals = jdbc.sql(WINDOW)
                .param("owner", owner.id())
                .param("seconds", seconds)
                .query((rs, row) -> new Totals(rs.getInt("calls"), rs.getBigDecimal("cost"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens")))
                .single();

        List<ModelTotals> byModel = jdbc.sql(BY_MODEL)
                .param("owner", owner.id())
                .param("seconds", seconds)
                .query((rs, row) -> new ModelTotals(rs.getString("model_id"), rs.getInt("calls"),
                        rs.getBigDecimal("cost"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")))
                .list();

        List<DayTotals> daily = jdbc.sql(DAILY)
                .param("owner", owner.id())
                .query((rs, row) -> new DayTotals(rs.getObject("day", LocalDate.class),
                        rs.getInt("calls"), rs.getBigDecimal("cost")))
                .list();

        BigDecimal cap = limit.cap();
        // A cap of zero means no cap, so there is no remainder to report rather than a
        // negative one. Over-spend reports zero remaining instead of a negative number,
        // since the cap bounds spend already incurred and the last request is allowed to
        // cross it.
        BigDecimal remaining = cap.signum() <= 0
                ? null
                : cap.subtract(totals.cost()).max(BigDecimal.ZERO);

        return new UsageSummary(window.toHours(), cap.signum() <= 0 ? null : cap,
                totals.cost(), remaining, totals.calls(), totals.inputTokens(),
                totals.outputTokens(), byModel, daily);
    }

    private record Totals(int calls, BigDecimal cost, long inputTokens, long outputTokens) {}

    record ModelTotals(String modelId, int calls, BigDecimal costUsd, long inputTokens,
                       long outputTokens) {}

    record DayTotals(LocalDate day, int calls, BigDecimal costUsd) {}

    /** {@code capUsd} and {@code remainingUsd} are null when no cap is configured. */
    record UsageSummary(long windowHours, BigDecimal capUsd, BigDecimal spentUsd,
                        BigDecimal remainingUsd, int calls, long inputTokens,
                        long outputTokens, List<ModelTotals> byModel, List<DayTotals> daily) {}
}
