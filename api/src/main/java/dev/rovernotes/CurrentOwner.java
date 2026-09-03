package dev.rovernotes;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the owner of the current request.
 *
 * <p>{@code ownerId} is the JWT subject, which since docs/ARCHITECTURE.md is an account this service
 * issued and a foreign key into its own {@code users} table. Every query in every module
 * filters on it; there is no path that reads another owner's rows, and the file endpoint
 * resolves a document through here before asking object storage for anything, because the
 * store has no idea who is asking.
 */
@Component
public class CurrentOwner {

    private final UUID devOwnerId;

    CurrentOwner(@Value("${rover.security.dev-owner-id:}") String devOwnerId) {
        this.devOwnerId = (devOwnerId == null || devOwnerId.isBlank())
                ? null
                : UUID.fromString(devOwnerId);
    }

    /**
     * @throws IllegalStateException if there is no authenticated principal and no
     *         local-profile fallback configured — failing closed rather than
     *         silently returning a default owner.
     */
    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (devOwnerId != null) {
            return devOwnerId;
        }
        throw new IllegalStateException("No authenticated owner on the current request");
    }
}
