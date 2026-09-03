package dev.rovernotes.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the private half of a signing key before it is written to the database.
 *
 * <p>Done in the application rather than with {@code pgcrypto} so that neither the
 * plaintext key nor the passphrase is ever sent to PostgreSQL, where either could reach a
 * statement log or a replica. What the database holds is a ciphertext it has no means of
 * reading, so a dump on its own does not confer the ability to mint tokens.
 *
 * <p>AES-256-GCM: authenticated, so a modified ciphertext fails to decrypt rather than
 * producing a different key. The nonce is random per encryption and stored in front of the
 * ciphertext, which is standard and is safe here because a key is encrypted once when it
 * is created.
 */
@Component
class KeyEncryption {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    KeyEncryption(@Value("${rover.identity.key-encryption-key:}") String base64Key) {
        // Fails at startup rather than at the first sign-in. A service that cannot read
        // its signing keys cannot issue tokens, and finding that out on boot is better
        // than finding it out when someone tries to log in.
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "rover.identity.key-encryption-key is not set. Supply 32 bytes, base64 "
                            + "encoded; generate one with: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "rover.identity.key-encryption-key is not valid base64", notBase64);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("rover.identity.key-encryption-key must decode to "
                    + KEY_BYTES + " bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    byte[] encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
            return out;
        } catch (Exception failure) {
            throw new IllegalStateException("could not encrypt a signing key", failure);
        }
    }

    String decrypt(byte[] stored) {
        try {
            GCMParameterSpec spec =
                    new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plaintext = cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            // The message deliberately says nothing about the key or the ciphertext.
            throw new IllegalStateException("could not decrypt a signing key", failure);
        }
    }
}
