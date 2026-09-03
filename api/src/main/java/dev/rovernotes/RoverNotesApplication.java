package dev.rovernotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Rover Notes 2.0 — a modular monolith.
 *
 * <p>Each direct subpackage of {@code dev.rovernotes} is a Spring Modulith application
 * module with an enforced boundary: a module may use another module's exported API,
 * never its internals. {@code ModularityTests} fails the build if that is violated.
 *
 * <p>Modules are deliberately structured so that any one of them can be extracted into
 * its own deployable when — and only when — its scaling profile diverges. See
 * {@code `docs/ARCHITECTURE.md`}.
 *
 * <p>Scheduling is enabled for one job: retrying indexing work that failed, bounded by
 * attempt count. See {@code IngestionRecovery}.
 */
@Modulith(systemName = "Rover Notes")
@EnableScheduling
@SpringBootApplication
public class RoverNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoverNotesApplication.class, args);
    }
}
