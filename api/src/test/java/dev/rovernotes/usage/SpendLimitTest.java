package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The cap, and when it releases.
 *
 * <p>Spend is inserted directly rather than accrued through a model, so the arithmetic can
 * be checked against amounts and ages chosen for the purpose. The retry hint is the part
 * worth testing hardest: it is derived from when individual charges age out of a rolling
 * window, and a hint that is quietly wrong sends a client back either too early, into the
 * same refusal, or far later than it needed to wait.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"rover.usage.cap-usd=1.00", "rover.usage.window=24h"})
class SpendLimitTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    SpendLimit limit;

    @Autowired
    JdbcClient jdbc;

    private final UUID owner = UUID.randomUUID();
    private final UUID somebodyElse = UUID.randomUUID();

    @BeforeEach
    void clear() {
        jdbc.sql("delete from llm_usage").update();
        // owner_id references users from V3, so both owners are real accounts. Spend
        // attributed to an account that never existed is not a case worth protecting.
        dev.rovernotes.TestAccounts.create(jdbc, owner);
        dev.rovernotes.TestAccounts.create(jdbc, somebodyElse);
    }

    /** Records spend for an owner, aged by the given number of hours. */
    private void spend(UUID who, String usd, double hoursAgo) {
        jdbc.sql("""
                        insert into llm_usage (owner_id, model_id, task, cost_usd, created_at)
                        values (:owner, 'anthropic/claude-sonnet-5', 'synthesis',
                                cast(:cost as numeric),
                                now() - make_interval(secs => :seconds))
                        """)
                .param("owner", who)
                .param("cost", usd)
                .param("seconds", (long) (hoursAgo * 3600))
                .update();
    }

    @Test
    void spendBelowTheCapPasses() {
        spend(owner, "0.90", 1);

        assertThatCode(() -> limit.check(owner)).doesNotThrowAnyException();
    }

    @Test
    void spendAtTheCapIsRefused() {
        spend(owner, "1.00", 1);

        assertThatThrownBy(() -> limit.check(owner)).isInstanceOf(SpendLimitExceeded.class);
    }

    @Test
    void spendOutsideTheWindowDoesNotCount() {
        // The window rolls, so a charge older than it has no bearing on what is allowed now.
        spend(owner, "5.00", 25);

        assertThatCode(() -> limit.check(owner)).doesNotThrowAnyException();
    }

    @Test
    void oneOwnersSpendDoesNotCapAnother() {
        spend(somebodyElse, "10.00", 1);

        assertThatCode(() -> limit.check(owner)).doesNotThrowAnyException();
    }

    @Test
    void theRefusalCarriesWhatWasSpentAndTheCap() {
        spend(owner, "1.50", 1);

        assertThatThrownBy(() -> limit.check(owner))
                .isInstanceOf(SpendLimitExceeded.class)
                .extracting(thrown -> ((SpendLimitExceeded) thrown).spent().doubleValue())
                .isEqualTo(1.50);
    }

    @Test
    void theHintWaitsForTheChargeThatBringsSpendBackUnderTheCap() {
        // Two charges of $0.75, one 20 hours old and one 2 hours old, against a $1.00 cap.
        // Shedding the older one leaves $0.75, which is under. So the wait is until that
        // charge leaves the window: 24 - 20 = 4 hours.
        spend(owner, "0.75", 20);
        spend(owner, "0.75", 2);

        long seconds = capturedHint();
        assertThat(seconds).isBetween(3 * 3600L + 3500, 4 * 3600L + 100);
    }

    @Test
    void theHintSkipsChargesThatWouldNotBeEnoughOnTheirOwn() {
        // Three charges against a $1.00 cap: $0.10 at 23 hours, $0.10 at 22, $1.20 at 10.
        // Total is $1.40. Shedding the first two leaves $1.20, still over, so the wait runs
        // to the third charge: 24 - 10 = 14 hours. A hint keyed on the oldest charge alone
        // would have said one hour and sent the caller back into the same refusal.
        spend(owner, "0.10", 23);
        spend(owner, "0.10", 22);
        spend(owner, "1.20", 10);

        long seconds = capturedHint();
        assertThat(seconds).isBetween(13 * 3600L + 3500, 14 * 3600L + 100);
    }

    @Test
    void theHintIsNeverZero() {
        // A charge on the point of expiry would otherwise report "retry now", and a client
        // that did so would meet the same refusal.
        spend(owner, "2.00", 23.9999);

        assertThat(capturedHint()).isGreaterThanOrEqualTo(1);
    }

    private long capturedHint() {
        try {
            limit.check(owner);
        } catch (SpendLimitExceeded exceeded) {
            return exceeded.retryAfterSeconds();
        }
        throw new AssertionError("expected the cap to be reached");
    }
}
