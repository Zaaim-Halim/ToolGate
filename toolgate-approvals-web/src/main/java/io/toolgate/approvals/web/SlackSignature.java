package io.toolgate.approvals.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Slack's request signature: {@code v0=} and the hex HMAC-SHA256, keyed by the app's signing secret, of
 * {@code v0:<timestamp>:<raw body>}, with the timestamp within five minutes.
 */
final class SlackSignature {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private SlackSignature() {}

    /**
     * Whether a request came through Slack, recently.
     *
     * @param secret    the signing secret
     * @param timestamp the {@code X-Slack-Request-Timestamp} header, in seconds
     * @param signature the {@code X-Slack-Signature} header
     * @param body      the raw body, exactly as received
     * @param now       the current time
     * @return {@code true} if the signature matches and the timestamp is within five minutes of now
     */
    static boolean valid(String secret, String timestamp, String signature, byte[] body, Instant now) {
        if (secret == null || secret.isBlank() || timestamp == null || signature == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException unreadable) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - seconds) > TOLERANCE.toSeconds()) {
            return false;
        }
        byte[] expected = sign(secret, Long.toString(seconds), body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.strip().getBytes(StandardCharsets.US_ASCII));
    }

    /** The signature Slack sends for a body at a timestamp. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("every JDK has HmacSHA256", impossible);
        }
    }
}
