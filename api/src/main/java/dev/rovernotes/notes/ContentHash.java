package dev.rovernotes.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 content hashing — the basis of ingestion idempotency.
 *
 * <p>Cheap to compute and stable across runs, so skipping unchanged content needs
 * nothing more than a hash comparison.
 */
public final class ContentHash {

    private ContentHash() {}

    public static String of(String content) {
        String normalized = content == null ? "" : content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; this branch is unreachable on any JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
