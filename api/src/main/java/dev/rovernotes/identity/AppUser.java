package dev.rovernotes.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * An account, as stored.
 *
 * <p>{@code id} is the value every other table already carries as {@code owner_id}, and
 * it is the subject of every token issued for this account. One identifier for the person
 * across identity, data and spend means a session can be joined to what it did.
 *
 * @param passwordHash always the encoded form, carrying its algorithm as a prefix. There
 *                     is no constructor path that accepts a plaintext password.
 */
record AppUser(UUID id, String email, String passwordHash, String displayName,
               Instant emailVerifiedAt, Instant disabledAt, int failedLogins,
               Instant lockedUntil) {

    boolean verified() {
        return emailVerifiedAt != null;
    }

    boolean disabled() {
        return disabledAt != null;
    }

    /** Locked only while the lock is in the future; the lock lapses without being cleared. */
    boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
