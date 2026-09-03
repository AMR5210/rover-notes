package dev.rovernotes.identity;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the account the {@code local} profile attributes every request to.
 *
 * <p>{@code owner_id} is a foreign key to {@code users} from V3 onward, so the fixed
 * development owner has to exist before anything can be written under it. Nothing else in
 * the local profile creates it: there is no sign-up flow yet, and the profile skips
 * authentication entirely.
 *
 * <p>Done from application code under {@code @Profile("local")} rather than from a
 * migration in a second Flyway location. Both work, but a location that only some profiles
 * read means the schema history differs between a developer's database and a deployed one,
 * and Flyway then refuses to validate whichever it was not built for. Keeping the migration
 * set identical everywhere is worth more than the few lines this costs.
 *
 * <p>Runs on context refresh rather than as an {@code ApplicationRunner}, and declares
 * its dependency on database initialisation so it cannot run before Flyway. A runner would
 * be the more natural place, but whether one executes depends on how the context was
 * started, and a development owner that appears when the application is launched and not
 * when a test starts it is the kind of difference that shows up as an unrelated failure.
 *
 * <p>The password is not usable. It is a random value that is discarded, because this
 * account is reached by bypassing authentication rather than by signing in — a known
 * development password would be a credential that works wherever the profile is
 * accidentally enabled.
 */
@Component
@Profile("local")
@DependsOnDatabaseInitialization
class LocalDevelopmentOwner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LocalDevelopmentOwner.class);

    private static final String INSERT = """
            insert into users (id, email, password_hash, display_name, email_verified_at)
            values (:id, :email, :hash, 'Local development', now())
            on conflict (id) do nothing
            """;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final UUID ownerId;

    LocalDevelopmentOwner(JdbcClient jdbc, PasswordEncoder passwords,
                          @Value("${rover.security.dev-owner-id}") UUID ownerId) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.ownerId = ownerId;
    }

    @Override
    public void afterPropertiesSet() {
        int created = jdbc.sql(INSERT)
                .param("id", ownerId)
                .param("email", "local-development@rover.invalid")
                .param("hash", passwords.encode(UUID.randomUUID().toString()))
                .update();

        if (created > 0) {
            log.info("Created the local development owner {}. This profile is never active "
                    + "in a deployed environment.", ownerId);
        }
    }
}
