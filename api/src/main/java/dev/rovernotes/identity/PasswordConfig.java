package dev.rovernotes.identity;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * How a password is stored.
 *
 * <p>Argon2id, which is memory-hard: the cost of a guess is bounded by memory bandwidth
 * rather than by clock speed alone, so the advantage a GPU or an ASIC has over the server
 * that set the parameters is much smaller than it is against an iterated hash.
 *
 * <p>Wrapped in a {@link DelegatingPasswordEncoder} so every stored hash carries the
 * algorithm that produced it as a {@code {argon2}} prefix. Two things follow. Parameters
 * can be raised without invalidating existing hashes, because an old hash still says how
 * to verify itself. And an algorithm can be replaced by adding an entry here rather than
 * by a migration, which is what the {@code bcrypt} entry below is for: it verifies
 * anything already stored under that scheme without being able to produce more of it.
 */
@Configuration
class PasswordConfig {

    /**
     * OWASP's Argon2id baseline: 19 MiB of memory, two passes, one lane.
     *
     * <p>Set explicitly rather than taken from {@code defaultsForSpringSecurity_v5_8()},
     * which uses 16 MiB. The difference is small, and stating the numbers means a later
     * change to them is a visible decision with a recorded reason instead of a library
     * default moving underneath the system.
     *
     * <p>Memory is the parameter to raise first. Passes trade linearly against attacker
     * cost; memory trades against the hardware an attacker can bring at all.
     */
    private static final int MEMORY_KIB = 19456;

    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;

    /** 128 bits of salt and a 256-bit tag, both at the sizes the Argon2 RFC specifies. */
    private static final int SALT_BYTES = 16;

    private static final int HASH_BYTES = 32;

    @Bean
    PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

        // Only argon2 encodes. The others are here so a hash produced under them can
        // still be verified, which is what makes replacing an algorithm possible without
        // locking every existing account out on the day of the change.
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder());

        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
