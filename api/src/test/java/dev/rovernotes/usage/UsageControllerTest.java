package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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
 * The spend an owner can see about themselves.
 *
 * <p>The figure reported here has to be the one the cap is enforced against, or a caller
 * is refused at a number the interface never showed them.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"rover.usage.cap-usd=1.00", "rover.usage.window=24h"})
class UsageControllerTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /** The owner the local profile attributes every request to. */
    private static final UUID DEV_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    UsageController controller;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        jdbc.sql("delete from llm_usage").update();
    }

    private void spend(UUID who, String model, String usd, int inputTokens, double hoursAgo) {
        jdbc.sql("""
                        insert into llm_usage (owner_id, model_id, task, cost_usd, input_tokens,
                                               output_tokens, created_at)
                        values (:owner, :model, 'synthesis', cast(:cost as numeric), :input, 10,
                                now() - make_interval(secs => :seconds))
                        """)
                .param("owner", who)
                .param("model", model)
                .param("cost", usd)
                .param("input", inputTokens)
                .param("seconds", (long) (hoursAgo * 3600))
                .update();
    }

    @Test
    void reportsSpendAndWhatIsLeftOfTheCap() {
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "0.30", 1000, 2);

        var summary = controller.summary();
        assertThat(summary.spentUsd().doubleValue()).isEqualTo(0.30);
        assertThat(summary.capUsd().doubleValue()).isEqualTo(1.00);
        assertThat(summary.remainingUsd().doubleValue()).isEqualTo(0.70);
        assertThat(summary.windowHours()).isEqualTo(24);
        assertThat(summary.calls()).isEqualTo(1);
    }

    @Test
    void countsOnlySpendInsideTheWindowTheCapUses() {
        // The reported figure has to match what a refusal would be based on, or the two
        // disagree at exactly the moment a caller looks.
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "0.30", 1000, 2);
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "9.00", 1000, 30);

        assertThat(controller.summary().spentUsd().doubleValue()).isEqualTo(0.30);
    }

    @Test
    void showsNobodyElsesSpend() {
        spend(dev.rovernotes.TestAccounts.create(jdbc), "anthropic/claude-sonnet-5", "5.00", 1000, 1);

        var summary = controller.summary();
        assertThat(summary.spentUsd().doubleValue()).isZero();
        assertThat(summary.calls()).isZero();
    }

    @Test
    void breaksSpendDownByModel() {
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "0.40", 2000, 1);
        spend(DEV_OWNER, "anthropic/claude-haiku-4-5", "0.10", 3000, 1);

        var byModel = controller.summary().byModel();
        assertThat(byModel).hasSize(2);
        // Ordered by cost, so the model worth looking at first is first.
        assertThat(byModel.get(0).modelId()).isEqualTo("anthropic/claude-sonnet-5");
        assertThat(byModel.get(0).inputTokens()).isEqualTo(2000);
    }

    @Test
    void reportsDailyTotalsOverTheLastWeek() {
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "0.10", 100, 1);
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "0.20", 100, 49);

        // Two different days, and days with no spend are absent rather than invented.
        assertThat(controller.summary().daily()).hasSize(2);
    }

    @Test
    void remainingIsZeroRatherThanNegativeOnceTheCapIsPassed() {
        // The cap bounds spend already incurred, so the request that crosses it is allowed
        // and the next is refused. A negative remainder would describe a debt that the
        // enforcement does not actually create.
        spend(DEV_OWNER, "anthropic/claude-sonnet-5", "1.50", 1000, 1);

        assertThat(controller.summary().remainingUsd().doubleValue()).isZero();
    }
}
