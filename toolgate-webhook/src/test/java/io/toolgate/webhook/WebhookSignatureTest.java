package io.toolgate.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** What a receiver accepts: the right secret, the exact body, a recent time. */
class WebhookSignatureTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Instant SENT = Instant.parse("2026-10-11T12:00:00Z");
    private static final String BODY = "{\"type\":\"approval.deferred\",\"approvalId\":\"a-1\"}";

    private final String header = WebhookSignature.header(SECRET, SENT, BODY);

    @Test
    void acceptsTheDeliveryAsSent() {
        assertThat(header).matches("t=1791720000,v1=[0-9a-f]{64}");
        assertThat(verifyAt(SENT.plusSeconds(30), header, BODY)).isTrue();
    }

    @Test
    void rejectsAnAlteredBodyOrAnotherSecret() {
        assertThat(verifyAt(SENT, header, BODY.replace("a-1", "a-2"))).isFalse();
        byte[] other = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.verify(other, header, BODY, Duration.ofMinutes(5), Clock.fixed(SENT, ZoneOffset.UTC)))
                .isFalse();
    }

    /** A captured delivery sent again later fails, even with its valid signature. */
    @Test
    void rejectsAReplay() {
        assertThat(verifyAt(SENT.plus(Duration.ofMinutes(6)), header, BODY)).isFalse();
        // Moving the timestamp forward breaks the signature instead.
        String moved = header.replace("t=1791720000", "t=1791720360");
        assertThat(verifyAt(SENT.plus(Duration.ofMinutes(6)), moved, BODY)).isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(verifyAt(SENT, null, BODY)).isFalse();
        assertThat(verifyAt(SENT, "", BODY)).isFalse();
        assertThat(verifyAt(SENT, "t=abc,v1=00", BODY)).isFalse();
        assertThat(verifyAt(SENT, "t=1791720000", BODY)).isFalse();
        assertThat(verifyAt(SENT, "t=1791720000,v1=zz", BODY)).isFalse();
    }

    private static boolean verifyAt(Instant now, String header, String body) {
        return WebhookSignature.verify(SECRET, header, body, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
    }
}
