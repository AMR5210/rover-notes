package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a model call costs, and who it belongs to.
 *
 * <p>The arithmetic is worth testing directly because it is the part nobody looks at
 * again: a rate applied to the wrong token class is invisible in a total that still looks
 * plausible.
 */
@SpringBootTest
@ActiveProfiles("local")
class UsageRecorderTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    UsageRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void clear() {
        jdbc.sql("delete from llm_usage").update();
        dev.rovernotes.TestAccounts.create(jdbc, owner);
    }

    /**
     * A usage report shaped like the one the Anthropic model produces.
     *
     * <p>{@code DefaultUsage} is not usable here: {@code Usage} declares the two cache
     * accessors as default methods returning null, and the provider implementation
     * overrides them. Building a fixture from the default would test an object no
     * provider returns, and would report cache pricing as working when it is untested.
     */
    private record ProviderUsage(Integer getPromptTokens, Integer getCompletionTokens,
                                 Long getCacheReadInputTokens, Long getCacheWriteInputTokens)
            implements org.springframework.ai.chat.metadata.Usage {

        @Override
        public Object getNativeUsage() {
            return this;
        }
    }

    private static ChatResponseMetadata metadata(String model, int in, int out,
                                                 long cacheRead, long cacheWrite) {
        return ChatResponseMetadata.builder()
                .id("req_test")
                .model(model)
                .usage(new ProviderUsage(in, out, cacheRead, cacheWrite))
                .build();
    }

    private Map<String, Object> onlyRow() {
        return jdbc.sql("select * from llm_usage").query().singleRow();
    }

    @Test
    void recordsTokensAgainstTheOwnerAndTask() {
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-sonnet-5", 4000, 200, 0, 0), 1200, 800));

        Map<String, Object> row = onlyRow();
        assertThat(row.get("owner_id")).isEqualTo(owner);
        assertThat(row.get("task")).isEqualTo("synthesis");
        assertThat(row.get("request_id")).isEqualTo("req_test");
        assertThat(row.get("input_tokens")).isEqualTo(4000);
        assertThat(row.get("output_tokens")).isEqualTo(200);
        assertThat(row.get("latency_ms")).isEqualTo(1200);
        assertThat(row.get("ttft_ms")).isEqualTo(800);
    }

    @Test
    void mapsTheProviderModelNameOntoTheRegistryIdentifier() {
        // The response says claude-sonnet-5; the registry keys on the provider-qualified
        // form so two providers offering the same name stay distinct. The column is a
        // foreign key, so an unmapped name would fail the insert.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-sonnet-5", 100, 10, 0, 0), 10, null));

        assertThat(onlyRow().get("model_id")).isEqualTo("anthropic/claude-sonnet-5");
    }

    @Test
    void pricesTheCallFromTheRegistryRatherThanFromCode() {
        // Sonnet 5 is registered at $3 per million in and $15 out.
        // 1,000,000 in + 100,000 out = 3.00 + 1.50 = 4.50.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-sonnet-5", 1_000_000, 100_000, 0, 0), 10, null));

        assertThat((BigDecimal) onlyRow().get("cost_usd"))
                .isEqualByComparingTo(new BigDecimal("4.500000"));
    }

    @Test
    void cacheReadsAreChargedAtAFractionOfTheInputRate() {
        // A million cache reads at 10% of the $3 input rate is $0.30, where counting them
        // as ordinary input would record $3.00.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-sonnet-5", 0, 0, 1_000_000, 0), 10, null));

        assertThat((BigDecimal) onlyRow().get("cost_usd"))
                .isEqualByComparingTo(new BigDecimal("0.300000"));
        // The column is an int, so the driver hands back an Integer.
        assertThat(onlyRow().get("cache_read_tokens")).isEqualTo(1_000_000);
    }

    @Test
    void cacheWritesAreChargedAboveTheInputRate() {
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-sonnet-5", 0, 0, 0, 1_000_000), 10, null));

        assertThat((BigDecimal) onlyRow().get("cost_usd"))
                .isEqualByComparingTo(new BigDecimal("3.750000"));
    }

    @Test
    void differentModelsArePricedDifferently() {
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.JUDGE,
                metadata("claude-haiku-4-5", 1_000_000, 0, 0, 0), 10, null));

        assertThat((BigDecimal) onlyRow().get("cost_usd"))
                .isEqualByComparingTo(new BigDecimal("1.000000"));
    }

    @Test
    void aPinnedSnapshotAttributesToTheModelItPins() {
        // Requesting an alias can return a dated identifier: asking for claude-haiku-4-5
        // produced claude-haiku-4-5-20251001. It is the same model at a fixed version and
        // belongs against the same registry row, and before this was handled the call
        // violated the foreign key and went unrecorded.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("claude-haiku-4-5-20251001", 1_000_000, 0, 0, 0), 10, null));

        Map<String, Object> row = onlyRow();
        assertThat(row.get("model_id")).isEqualTo("anthropic/claude-haiku-4-5");
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(new BigDecimal("1.000000"));
    }

    @Test
    void anUnregisteredModelIsNotRecorded() {
        // The column is a foreign key, so there is nowhere to put a model the registry
        // does not know. A logged gap is better than a row no price can apply to.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                metadata("some-other-provider-model", 100, 10, 0, 0), 10, null));

        assertThat(jdbc.sql("select count(*) from llm_usage").query(Long.class).single()).isZero();
    }

    @Test
    void aResponseWithoutUsageIsStillRecorded() {
        // A provider that omits usage should leave a row saying so rather than no row at
        // all, since a missing row is indistinguishable from a call that never happened.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS,
                ChatResponseMetadata.builder().model("claude-sonnet-5").build(), 10, null));

        Map<String, Object> row = onlyRow();
        assertThat(row.get("input_tokens")).isEqualTo(0);
        assertThat(row.get("output_tokens")).isEqualTo(0);
    }

    @Test
    void aFailureToRecordDoesNotPropagate() {
        // Accounting must not fail a request that has already been answered. A null
        // metadata leaves no model to key on, and the insert violates a not-null column.
        recorder.record(new UsageRecorder.Call(owner, UsageRecorder.Task.SYNTHESIS, null, 10, null));

        assertThat(jdbc.sql("select count(*) from llm_usage").query(Long.class).single()).isZero();
    }
}
