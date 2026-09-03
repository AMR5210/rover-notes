package dev.rovernotes.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one row per model call, so spend is a query rather than an estimate.
 *
 * <p>Micrometer already counts tokens through {@code gen_ai.client.token.usage}, but a
 * counter answers only "how much in total". It cannot say which owner, which question or
 * which model a cost belongs to, which is what a per-user cap or a model comparison needs.
 *
 * <p>Cost is computed from the {@code models} table rather than from a constant in code,
 * so a price change is a row update. The figure is list price: Claude Sonnet 5 is on
 * introductory pricing until 2026-08-31, so actual billing is currently lower than what is
 * recorded here. Encoding a dated discount in the registry would leave a value that is
 * wrong from the day it lapses, and list price is the number that stays reproducible.
 *
 * <p>Cache reads are stored separately because they bill at roughly a tenth of the input
 * rate. Folding them into input tokens would overstate spend on any path that caches a
 * prompt prefix.
 */
@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /** Cache reads bill at about 10% of the input rate on the Anthropic API. */
    private static final BigDecimal CACHE_READ_RATE = new BigDecimal("0.10");

    /** A cache write bills at about 1.25x the input rate. */
    private static final BigDecimal CACHE_WRITE_RATE = new BigDecimal("1.25");

    private static final String INSERT = """
            insert into llm_usage (owner_id, request_id, model_id, task, input_tokens,
                                   output_tokens, cache_read_tokens, cache_write_tokens,
                                   cost_usd, latency_ms, ttft_ms)
            values (:owner, :request, :model, :task, :input, :output, :cacheRead,
                    :cacheWrite, :cost, :latency, :ttft)
            """;

    private static final String PRICES = """
            select cost_in_per_mtok, cost_out_per_mtok from models where id = :id
            """;

    private final JdbcClient jdbc;

    UsageRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a completed model call.
     *
     * <p>Runs in its own transaction and swallows its own failures. Accounting must not
     * be able to fail a request that has already been answered: the answer is the product
     * and the row is bookkeeping, so a missing row is a gap in reporting rather than an
     * error for the person who asked the question. Failures are logged so the gap is
     * visible.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Call call) {
        try {
            Optional<String> registered = registryId(call.metadata());
            if (registered.isEmpty()) {
                // The column is a foreign key into the registry, so a model with no row
                // there cannot be recorded at all. Loosening the constraint would allow
                // rows that no price can ever be applied to, which is worse than a gap
                // that says so.
                log.warn("no registry entry for model {}; usage for task {} not recorded",
                        call.metadata() == null ? null : call.metadata().getModel(), call.task());
                return;
            }

            String modelId = registered.get();
            Tokens tokens = Tokens.from(call.metadata());

            jdbc.sql(INSERT)
                    .param("owner", call.ownerId())
                    .param("request", call.metadata() == null ? null : call.metadata().getId())
                    .param("model", modelId)
                    .param("task", call.task().value())
                    .param("input", tokens.input())
                    .param("output", tokens.output())
                    .param("cacheRead", tokens.cacheRead())
                    .param("cacheWrite", tokens.cacheWrite())
                    .param("cost", cost(modelId, tokens))
                    .param("latency", call.latencyMs())
                    .param("ttft", call.ttftMs())
                    .update();
        } catch (RuntimeException e) {
            log.warn("could not record usage for task {}: {}", call.task(), e.toString());
        }
    }

    /**
     * Maps the provider's model name onto the registry's identifier.
     *
     * <p>The response reports what the provider calls the model, such as
     * {@code claude-sonnet-5}, while the registry keys on {@code anthropic/claude-sonnet-5}
     * so that two providers offering the same name stay distinct.
     *
     * <p>An exact match is tried first, then the longest registered name the report starts
     * with. Requesting an alias can return a pinned snapshot: asking for
     * {@code claude-haiku-4-5} produced {@code claude-haiku-4-5-20251001}, which is the
     * same model at a fixed version and belongs against the same registry row and prices.
     * Without the prefix step that call violated the foreign key and went unrecorded.
     *
     * <p>Longest match rather than any match, because one registered name can be a prefix
     * of another and the more specific row is the right one.
     */
    private Optional<String> registryId(ChatResponseMetadata metadata) {
        String reported = metadata == null ? null : metadata.getModel();
        if (reported == null || reported.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select id from models
                        where :name = model_id or :name like model_id || '-%'
                        order by length(model_id) desc
                        limit 1
                        """)
                .param("name", reported)
                .query(String.class)
                .optional();
    }

    private BigDecimal cost(String modelId, Tokens tokens) {
        Optional<Prices> prices = jdbc.sql(PRICES)
                .param("id", modelId)
                .query((rs, row) -> new Prices(rs.getBigDecimal(1), rs.getBigDecimal(2)))
                .optional();

        if (prices.isEmpty() || prices.get().in() == null || prices.get().out() == null) {
            // A model the registry does not price is still worth recording in tokens.
            return null;
        }

        BigDecimal in = prices.get().in();
        BigDecimal out = prices.get().out();

        BigDecimal total = in.multiply(BigDecimal.valueOf(tokens.input()))
                .add(out.multiply(BigDecimal.valueOf(tokens.output())))
                .add(in.multiply(CACHE_READ_RATE).multiply(BigDecimal.valueOf(tokens.cacheRead())))
                .add(in.multiply(CACHE_WRITE_RATE)
                        .multiply(BigDecimal.valueOf(tokens.cacheWrite())));

        return total.divide(PER_MILLION, 6, RoundingMode.HALF_UP);
    }

    private record Prices(BigDecimal in, BigDecimal out) {}

    /** What a task is called in the {@code task} column. */
    public enum Task {
        SYNTHESIS("synthesis"),
        CLASSIFY("classify"),
        CONTEXTUALIZE("contextualize"),
        JUDGE("judge"),
        AGENT("agent");

        private final String value;

        Task(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * One model call as the recorder needs it.
     *
     * <p>{@code ttftMs} is only meaningful for a streamed response, and is null otherwise:
     * a blocking call has no first token distinct from its last.
     */
    public record Call(UUID ownerId, Task task, ChatResponseMetadata metadata,
                       Integer latencyMs, Integer ttftMs) {}

    /** Token counts, defaulted to zero so a provider that omits one does not fail a row. */
    record Tokens(int input, int output, long cacheRead, long cacheWrite) {

        static Tokens from(ChatResponseMetadata metadata) {
            Usage usage = metadata == null ? null : metadata.getUsage();
            if (usage == null) {
                return new Tokens(0, 0, 0, 0);
            }
            return new Tokens(
                    usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                    usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                    usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens(),
                    usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens());
        }
    }
}
