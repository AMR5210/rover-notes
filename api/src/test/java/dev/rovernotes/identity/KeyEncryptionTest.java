package dev.rovernotes.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * What protects a signing key at rest.
 *
 * <p>No Spring context: this is arithmetic over bytes, and running it as a unit test means
 * the failure cases can be exercised in milliseconds rather than by booting an application
 * per case.
 */
class KeyEncryptionTest {

    private static String key(String seed) {
        byte[] raw = new byte[32];
        byte[] from = seed.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(from, 0, raw, 0, Math.min(from.length, raw.length));
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void returnsWhatItWasGiven() {
        KeyEncryption encryption = new KeyEncryption(key("a-key-for-this-test"));
        String secret = "{\"kty\":\"RSA\",\"d\":\"the-private-part\"}";

        assertThat(encryption.decrypt(encryption.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void storesSomethingThatDoesNotContainThePlaintext() {
        KeyEncryption encryption = new KeyEncryption(key("a-key-for-this-test"));

        byte[] stored = encryption.encrypt("the-private-part");

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("the-private-part");
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        // A fresh nonce per encryption. Identical ciphertexts would reveal that two keys
        // are the same without either being decrypted.
        KeyEncryption encryption = new KeyEncryption(key("a-key-for-this-test"));

        assertThat(encryption.encrypt("same")).isNotEqualTo(encryption.encrypt("same"));
    }

    @Test
    void refusesCiphertextThatHasBeenAlteredRatherThanReturningSomethingElse() {
        // The point of an authenticated mode. Without the tag, flipping a byte would yield
        // a different key that fails later and further away, at signing time.
        KeyEncryption encryption = new KeyEncryption(key("a-key-for-this-test"));
        byte[] stored = encryption.encrypt("the-private-part");
        stored[stored.length - 1] ^= 0x01;

        assertThatThrownBy(() -> encryption.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("the-private-part");
    }

    @Test
    void refusesADifferentKey() {
        byte[] stored = new KeyEncryption(key("the-original-key")).encrypt("the-private-part");

        assertThatThrownBy(() -> new KeyEncryption(key("a-different-key")).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAKey() {
        // Failing here rather than at the first sign-in. A service that cannot read its
        // signing keys cannot issue tokens, and boot is when that should be visible.
        assertThatThrownBy(() -> new KeyEncryption(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        String tooShort = Base64.getEncoder().encodeToString("sixteen-bytes-ok".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new KeyEncryption(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void refusesSomethingThatIsNotBase64() {
        assertThatThrownBy(() -> new KeyEncryption("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }
}
