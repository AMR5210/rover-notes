package dev.rovernotes;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates the account rows that owned data now requires.
 *
 * <p>From V3, {@code owner_id} is a foreign key to {@code users}, so a test that writes a
 * document has to write it for someone who exists. Before that constraint an arbitrary
 * {@link UUID} was enough, and several tests used one — which meant they were exercising
 * isolation against owners the system could never actually have.
 *
 * <p>Rows are inserted directly rather than through {@code UserService} so that tests in
 * modules with no reason to depend on identity do not acquire one, and so that a fixed
 * identifier can be supplied where a test already has one it wants to keep.
 */
public final class TestAccounts {

    private static final String INSERT = """
            insert into users (id, email, password_hash, display_name, email_verified_at)
            values (:id, :email, 'not-a-usable-hash', 'Test account', now())
            on conflict (id) do nothing
            """;

    private TestAccounts() {
    }

    /** An account with a fresh identifier. */
    public static UUID create(JdbcClient jdbc) {
        return create(jdbc, UUID.randomUUID());
    }

    /** An account with a given identifier, for a test that already fixed one. */
    public static UUID create(JdbcClient jdbc, UUID id) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("email", id + "@test.invalid")
                .update();
        return id;
    }
}
