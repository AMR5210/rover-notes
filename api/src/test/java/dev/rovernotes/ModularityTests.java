package dev.rovernotes;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the application's module structure on every build.
 *
 * <p>{@code verify()} fails if a module reaches into another module's internals rather
 * than its exported API, or if a dependency cycle appears between modules. Keeping
 * these boundaries green means a module can later be extracted into its own deployable
 * along a seam the compiler has been checking all along. See docs/ARCHITECTURE.md.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(RoverNotesApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void printModuleStructure() {
        MODULES.forEach(module -> System.out.println(module.getDisplayName()));
    }
}
