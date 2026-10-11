package io.toolgate.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and checks ToolGate's webhook deliveries.
 *
 * <p>The {@value #HEADER} header reads {@code t=<unix seconds>,v1=<hex HMAC-SHA256>}, where the MAC covers
 * {@code <t>.<body>} under the shared secret. A receiver recomputes it and also checks that {@code t} is
 * recent, which turns away a delivery captured and replayed later. Within the tolerance, a receiver that
 * must never act twice deduplicates on the {@value #DELIVERY_HEADER} header, which stays the same across
 * retries.
 *
 * <pre>{@code
 * if (!WebhookSignature.verify(secret, request.header("ToolGate-Signature"), body, Duration.ofMinutes(5))) {
 *     return 401;
 * }
 * }</pre>
 */
public final class WebhookSignature {

    /** The header carrying the timestamp and the signature. */
    public static final String HEADER = "ToolGate-Signature";
    /** The header carrying the delivery's ID, the same for every attempt. */
    public static final String DELIVERY_HEADER = "ToolGate-Delivery";
    /** Shorter secrets are refused: HMAC-SHA256 is only as strong as its key. */
    public static final int MIN_SECRET_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private WebhookSignature() {}

    /**
     * The signature header for a body sent at a given time.
     *
     * @param secret the shared secret, at least {@value #MIN_SECRET_BYTES} bytes
     * @param at     when it is sent
     * @param body   the exact body sent
     * @return the header's value
     */
    public static String header(byte[] secret, Instant at, String body) {
        long seconds = at.getEpochSecond();
        return "t=" + seconds + "," + VERSION + "=" + HexFormat.of().formatHex(mac(secret, seconds, body));
    }

    /**
     * Checks a delivery against the system clock.
     *
     * @param secret    the shared secret
     * @param header    the {@value #HEADER} header as received; null fails
     * @param body      the exact body received
     * @param tolerance how old, or how far ahead, a delivery may be, such as 5 minutes
     * @return whether the signature matches and the delivery is recent
     */
    public static boolean verify(byte[] secret, String header, String body, Duration tolerance) {
        return verify(secret, header, body, tolerance, Clock.systemUTC());
    }

    /**
     * Checks a delivery.
     *
     * @param secret    the shared secret
     * @param header    the {@value #HEADER} header as received; null fails
     * @param body      the exact body received
     * @param tolerance how old, or how far ahead, a delivery may be
     * @param clock     what "now" is
     * @return whether the signature matches and the delivery is recent
     */
    public static boolean verify(byte[] secret, String header, String body, Duration tolerance, Clock clock) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(tolerance, "tolerance");
        if (header == null) {
            return false;
        }
        Long seconds = null;
        byte[] signature = null;
        for (String part : header.split(",")) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                return false;
            }
            String name = part.substring(0, equals).strip();
            String value = part.substring(equals + 1).strip();
            try {
                if (name.equals("t")) {
                    seconds = Long.parseLong(value);
                } else if (name.equals(VERSION)) {
                    signature = HexFormat.of().parseHex(value);
                }
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
        if (seconds == null || signature == null) {
            return false;
        }
        long age = Math.abs(clock.instant().getEpochSecond() - seconds);
        // Compare the MAC in constant time, so its bytes cannot be guessed one by one from timings.
        return age <= tolerance.toSeconds() && MessageDigest.isEqual(mac(secret, seconds, body), signature);
    }

    static byte[] requireSecret(byte[] secret) {
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("a webhook secret needs at least " + MIN_SECRET_BYTES + " bytes");
        }
        return secret.clone();
    }

    private static byte[] mac(byte[] secret, long seconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(requireSecret(secret), ALGORITHM));
            return mac.doFinal((seconds + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 is required of every JDK", impossible);
        }
    }
}
