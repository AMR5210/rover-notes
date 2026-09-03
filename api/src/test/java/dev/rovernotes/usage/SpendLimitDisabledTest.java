package dev.rovernotes.usage;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** A cap of zero disables the check, which is what a deployment without billing wants. */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "rover.usage.cap-usd=0")
class SpendLimitDisabledTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    SpendLimit limit;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anyAmountOfSpendPassesWhenTheCapIsZero() {
        UUID owner = dev.rovernotes.TestAccounts.create(jdbc);
        jdbc.sql("""
                        insert into llm_usage (owner_id, model_id, task, cost_usd)
                        values (:owner, 'anthropic/claude-sonnet-5', 'synthesis', 999.00)
                        """)
                .param("owner", owner)
                .update();

        assertThatCode(() -> limit.check(owner)).doesNotThrowAnyException();
    }
}
