package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals values that stores keep at rest, such as queued calls' arguments and logged session
 * events, which quote real argument values.
 *
 * <p>Every sealed value is bound to a context naming its row, such as
 * {@code toolgate_pending_call:<approvalId>}: a ciphertext copied into another row fails to open,
 * so nobody with write access to the database can move an approved payload onto another call.
 */
public interface ValueCodec {

    /**
     * Seals a value for one row.
     *
     * @param plaintext the value
     * @param context   names the row the value belongs to; the same context must open it
     * @return the sealed text
     */
    String seal(String plaintext, String context);

    /**
     * Opens a value sealed for this row.
     *
     * @param sealed  what {@link #seal} returned
     * @param context the row's context, as given to {@link #seal}
     * @return the value
     * @throws IllegalArgumentException if the text was not sealed by this codec's keys, was changed,
     *                                  or belongs to another row
     */
    String open(String sealed, String context);

    /**
     * AES-256-GCM with a random 96-bit nonce per value, from the JDK alone.
     *
     * <p>Sealed text reads {@code tg1:<keyId>:<base64 of nonce, ciphertext and tag>}. The key ID
     * lets old values open after the key changes: seal with the new key, and keep the old ones with
     * {@link AesGcm#alsoOpening}. Rotate long before 2<sup>32</sup> values per key, the safe limit
     * for random nonces. Store keys in a vault or KMS; a lost key loses the data.
     *
     * @param keyId names the key in sealed text: letters, digits, {@code .}, {@code _} and {@code -}
     * @param key   32 secret bytes; copied
     * @return the codec
     * @throws IllegalArgumentException if the key is not 32 bytes or the key ID is not plain
     */
    static AesGcm aesGcm(String keyId, byte[] key) {
        return new AesGcm(keyId, key, Map.of());
    }

    /**
     * Stores values in clear, chosen deliberately: no protection at rest, and visible as such in review.
     *
     * @return the codec, which returns every value unchanged
     */
    static ValueCodec unencrypted() {
        return Unencrypted.INSTANCE;
    }

    /** Values in clear. */
    final class Unencrypted implements ValueCodec {

        private static final Unencrypted INSTANCE = new Unencrypted();

        private Unencrypted() {}

        @Override
        public String seal(String plaintext, String context) {
            return Objects.requireNonNull(plaintext, "plaintext");
        }

        @Override
        public String open(String sealed, String context) {
            if (sealed.startsWith(AesGcm.PREFIX)) {
                throw new IllegalArgumentException("value is encrypted; configure the key that sealed it");
            }
            return sealed;
        }
    }

    /** AES-256-GCM; see {@link ValueCodec#aesGcm}. */
    final class AesGcm implements ValueCodec {

        static final String PREFIX = "tg1:";
        private static final String ALGORITHM = "AES/GCM/NoPadding";
        private static final int KEY_BYTES = 32;
        private static final int NONCE_BYTES = 12;
        private static final int TAG_BITS = 128;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final String currentKeyId;
        private final Map<String, SecretKeySpec> keys;

        private AesGcm(String keyId, byte[] key, Map<String, SecretKeySpec> older) {
            requireKeyId(keyId);
            var all = new LinkedHashMap<String, SecretKeySpec>(older);
            if (all.containsKey(keyId)) {
                throw new IllegalArgumentException("key ID " + keyId + " is given twice");
            }
            all.put(keyId, secret(key));
            this.currentKeyId = keyId;
            this.keys = Map.copyOf(all);
        }

        /**
         * The same codec, also able to open values sealed with an older key. New values are still
         * sealed with the current key.
         *
         * @param keyId the older key's ID, as it appears in sealed text
         * @param key   the older key's 32 bytes
         * @return a codec that seals with the current key and opens with either
         */
        public AesGcm alsoOpening(String keyId, byte[] key) {
            requireKeyId(keyId);
            if (keys.containsKey(keyId)) {
                throw new IllegalArgumentException("key ID " + keyId + " is given twice");
            }
            var older = new LinkedHashMap<String, SecretKeySpec>(keys);
            older.remove(currentKeyId);
            older.put(keyId, secret(key));
            return new AesGcm(currentKeyId, keys.get(currentKeyId).getEncoded(), older);
        }

        @Override
        public String seal(String plaintext, String context) {
            Objects.requireNonNull(plaintext, "plaintext");
            requireEncodable(plaintext, "plaintext");
            requireEncodable(context, "context");
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), nonce, context)
                    .apply(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
            return PREFIX + currentKeyId + ":" + Base64.getEncoder().encodeToString(out);
        }

        @Override
        public String open(String sealed, String context) {
            if (!sealed.startsWith(PREFIX)) {
                // No silent plaintext reads: an unsealed value in an encrypted store is an error.
                throw new IllegalArgumentException("value is not sealed");
            }
            int separator = sealed.indexOf(':', PREFIX.length());
            if (separator < 0) {
                throw new IllegalArgumentException("sealed value has no key ID");
            }
            String keyId = sealed.substring(PREFIX.length(), separator);
            SecretKeySpec key = keys.get(keyId);
            if (key == null) {
                throw new IllegalArgumentException("no key with ID " + keyId + "; add it with alsoOpening");
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(sealed.substring(separator + 1));
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalArgumentException("sealed value is not base64", notBase64);
            }
            if (data.length < NONCE_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException("sealed value is too short");
            }
            byte[] nonce = Arrays.copyOf(data, NONCE_BYTES);
            byte[] body = Arrays.copyOfRange(data, NONCE_BYTES, data.length);
            return new String(cipher(Cipher.DECRYPT_MODE, key, nonce, context).apply(body), StandardCharsets.UTF_8);
        }

        /** One AES-GCM operation, with the context as associated data. */
        private static UnaryOperator<byte[]> cipher(int mode, SecretKeySpec key, byte[] nonce,
                                                   String context) {
            Objects.requireNonNull(context, "context");
            return input -> {
                try {
                    Cipher cipher = Cipher.getInstance(ALGORITHM);
                    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
                    cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
                    return cipher.doFinal(input);
                } catch (AEADBadTagException tampered) {
                    throw new IllegalArgumentException("sealed value was changed, or belongs to another row", tampered);
                } catch (GeneralSecurityException impossible) {
                    // Every Java platform provides AES-GCM.
                    throw new IllegalStateException(impossible);
                }
            };
        }

        /**
         * UTF-8 replaces an unpaired surrogate with '?', so such text would open as different text.
         * Refuse it instead of corrupting it; JSON from ArgValueJson escapes surrogates, so it never has one.
         */
        private static void requireEncodable(String text, String name) {
            for (int index = 0; index < text.length(); index++) {
                char c = text.charAt(index);
                if (Character.isHighSurrogate(c) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                    index++;
                } else if (Character.isSurrogate(c)) {
                    throw new IllegalArgumentException(name + " has an unpaired surrogate at " + index
                            + ", which UTF-8 cannot keep");
                }
            }
        }

        private static SecretKeySpec secret(byte[] key) {
            if (key == null || key.length != KEY_BYTES) {
                throw new IllegalArgumentException("an AES-256 key is exactly " + KEY_BYTES + " bytes");
            }
            return new SecretKeySpec(Arrays.copyOf(key, KEY_BYTES), "AES");
        }

        private static void requireKeyId(String keyId) {
            requireNonBlank(keyId, "keyId");
            if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("key ID must be letters, digits, '.', '_' or '-', was " + keyId);
            }
        }
    }
}
