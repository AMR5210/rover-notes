package dev.rovernotes;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres server for the whole test run, and a database of its own for each context.
 *
 * <p>Each test class used to declare its own {@code @Container}, which starts a server per
 * class and stops it afterwards. Twenty-six classes did, so a build spent most of its time
 * starting and stopping the same image: the Java job billed 18 minutes in CI against 12
 * seconds of aggregate test execution.
 *
 * <p>The server here is started once, from a static initializer, and deliberately never
 * stopped. Testcontainers' Ryuk sidecar removes it when the JVM exits, which is the same
 * guarantee {@code @Container} gives without tying the lifetime to one class.
 *
 * <h2>A database each, rather than one shared database</h2>
 *
 * <p>Sharing a single database across every class would be faster still, because Spring
 * could then reuse a cached context between classes that configure themselves identically.
 * It would also let one class see rows another wrote, and a suite where that matters fails
 * by order rather than by assertion — the kind of failure that reproduces only sometimes.
 *
 * <p>Creating a database is a local operation measured in milliseconds against roughly
 * thirty seconds for a container, so a database per context keeps the isolation each class
 * had before at a small fraction of the cost. Flyway then migrates each one, which is what
 * makes it a working database rather than an empty one.
 */
public final class TestDatabase {

    /**
     * One server, and enough connections for every context that shares it.
     *
     * <p>Postgres allows 100 clients by default, which was ample when each class had a
     * server to itself and is not when they share one: Spring caches up to 32 contexts and
     * each holds a pool. The pool is capped below too, so both halves of that product are
     * bounded rather than only one.
     *
     * <p>{@code fsync=off} is what {@code PostgreSQLContainer} sets when it is left to
     * build its own command, and it is repeated here because naming a command replaces
     * that one rather than adding to it. Durability across a restart is not a property any
     * test needs from a database that is discarded at the end of the run.
     */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg17")
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400");

    /** Names the databases apart. Contexts are built one at a time, but the counter is
     *  atomic so that stays true if the suite is ever forked across JVMs. */
    private static final AtomicInteger NEXT = new AtomicInteger();

    static {
        POSTGRES.start();
    }

    private TestDatabase() {
    }

    /**
     * Points the context under construction at a database created for it.
     *
     * <p>Called from a {@code @DynamicPropertySource} method, whose properties take
     * precedence over {@code application.yml} — so a test never reaches the development
     * database, whatever {@code POSTGRES_URL} is set to in the surrounding shell.
     */
    public static void register(DynamicPropertyRegistry registry) {
        String name = "rover_test_" + NEXT.incrementAndGet();
        create(name);
        registry.add("spring.datasource.url", () -> urlFor(name));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The application asks for 16, sized for concurrent requests it does not receive
        // here. A test context runs one thread at a time and the surplus is only a claim
        // on the shared server, held for as long as the context stays cached.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
    }

    private static void create(String name) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // The name is generated here rather than supplied, so it cannot carry anything
            // that would need quoting.
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the test database " + name, e);
        }
    }

    private static String urlFor(String name) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                name);
    }
}
