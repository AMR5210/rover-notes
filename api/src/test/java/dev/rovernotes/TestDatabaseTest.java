package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the shared container has to guarantee, checked rather than assumed.
 *
 * <p>Both properties below are invisible when they break. A registration that stopped
 * applying would leave the datasource on {@code application.yml}, which points at the
 * development database — the suite would keep passing while writing to it. And a database
 * handed to two contexts would let one class see another's rows, which fails by execution
 * order rather than by assertion.
 */
@SpringBootTest
@ActiveProfiles("local")
class TestDatabaseTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void runsAgainstADatabaseCreatedForTheRunRatherThanTheDevelopmentOne() {
        assertThat(jdbc.sql("select current_database()").query(String.class).single())
                .startsWith("rover_test_");
    }

    @Test
    void theDatabaseItIsGivenHasBeenMigrated() {
        // An empty database would fail every other test with a missing relation. Naming the
        // expectation here means the cause is reported once, rather than as the first
        // unrelated test to touch a table.
        assertThat(jdbc.sql("select to_regclass('public.documents')").query(String.class).single())
                .isEqualTo("documents");
    }

    @Test
    void eachRegistrationGetsADatabaseOfItsOwn() {
        assertThat(register()).isNotEqualTo(register());
    }

    /** The URL a context would be configured with, without building one. */
    private static String register() {
        List<String> urls = new ArrayList<>();
        TestDatabase.register((name, valueSupplier) -> {
            if ("spring.datasource.url".equals(name)) {
                urls.add(String.valueOf(((Supplier<?>) valueSupplier).get()));
            }
        });
        return urls.getFirst();
    }
}
