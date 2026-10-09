package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.spi.ValueCodec;
import java.util.Arrays;
import java.util.Base64;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class ValueCodecTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] NEW_KEY = "fedcba9876543210fedcba9876543210".getBytes();
    private static final String ROW = "toolgate_pending_call:refunds:c2";

    private final ValueCodec codec = ValueCodec.aesGcm("2026-07", KEY);

    @Test
    void sealsAndOpensWithItsRow() {
        String sealed = codec.seal("{\"amount\":480,\"to\":\"billing@mycompany.com\"}", ROW);

        assertThat(sealed).startsWith("tg1:2026-07:").doesNotContain("billing");
        assertThat(codec.open(sealed, ROW)).isEqualTo("{\"amount\":480,\"to\":\"billing@mycompany.com\"}");
    }

    @Test
    void sealingTheSameValueTwiceDiffers() {
        assertThat(codec.seal("same", ROW)).isNotEqualTo(codec.seal("same", ROW));
    }

    /** Someone with write access copies an approved payload onto another pending call. */
    @Test
    void aValueMovedToAnotherRowDoesNotOpen() {
        String sealed = codec.seal("{\"amount\":20}", ROW);

        assertThatIllegalArgumentException().isThrownBy(() -> codec.open(sealed, "toolgate_pending_call:refunds:c9"))
                .withMessageContaining("another row");
    }

    @Test
    void anyChangeIsDetected() {
        String sealed = codec.seal("{\"amount\":20}", ROW);
        String prefix = sealed.substring(0, sealed.lastIndexOf(':') + 1);
        byte[] data = Base64.getDecoder().decode(sealed.substring(prefix.length()));

        for (int index = 0; index < data.length; index++) {
            byte[] changed = Arrays.copyOf(data, data.length);
            changed[index] ^= 1;
            String tampered = prefix + Base64.getEncoder().encodeToString(changed);
            assertThatIllegalArgumentException().as("byte %d", index).isThrownBy(() -> codec.open(tampered, ROW));
        }
    }

    @Test
    void rejectsTextItDidNotSeal() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open("{\"amount\":20}", ROW))
                .withMessageContaining("not sealed");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open("tg1:2026-07", ROW));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open("tg1:2026-07:!!!", ROW));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open("tg1:2026-07:AAAA", ROW));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open(codec.seal("x", ROW).replace("2026-07", "2025-01"), ROW))
                .withMessageContaining("no key with ID 2025-01");
    }

    @Test
    void rotationSealsWithTheNewKeyAndStillOpensOldValues() {
        String old = codec.seal("old value", ROW);
        ValueCodec rotated = ValueCodec.aesGcm("2026-10", NEW_KEY).alsoOpening("2026-07", KEY);

        String fresh = rotated.seal("new value", ROW);

        assertThat(fresh).startsWith("tg1:2026-10:");
        assertThat(rotated.open(old, ROW)).isEqualTo("old value");
        assertThat(rotated.open(fresh, ROW)).isEqualTo("new value");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.open(fresh, ROW));
    }

    @Test
    void rejectsBadKeysAndKeyIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> ValueCodec.aesGcm("k", new byte[31]));
        assertThatIllegalArgumentException().isThrownBy(() -> ValueCodec.aesGcm("a:b", KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> ValueCodec.aesGcm(" ", KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> ValueCodec.aesGcm("k", KEY).alsoOpening("k", NEW_KEY));
    }

    @Test
    void theKeyIsCopied() {
        byte[] key = KEY.clone();
        ValueCodec copied = ValueCodec.aesGcm("k", key);
        String sealed = copied.seal("value", ROW);

        Arrays.fill(key, (byte) 0);

        assertThat(copied.open(sealed, ROW)).isEqualTo("value");
    }

    @Test
    void unencryptedIsExplicitAndRefusesSealedValues() {
        ValueCodec clear = ValueCodec.unencrypted();

        assertThat(clear.seal("value", ROW)).isEqualTo("value");
        assertThat(clear.open("value", ROW)).isEqualTo("value");
        assertThatIllegalArgumentException().isThrownBy(() -> clear.open(codec.seal("value", ROW), ROW))
                .withMessageContaining("encrypted");
    }

    /** Half an emoji would come back as '?': refused rather than silently changed. */
    @Test
    void refusesTextUtf8CannotKeep() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.seal("half \ud83d emoji", ROW))
                .withMessageContaining("unpaired surrogate");
        assertThat(codec.open(codec.seal("whole \ud83d\ude00 emoji", ROW), ROW)).isEqualTo("whole \ud83d\ude00 emoji");
    }

    @Property(tries = 300)
    void anyTextRoundTripsOrIsRefused(@ForAll String value, @ForAll String context) {
        try {
            assertThat(codec.open(codec.seal(value, context), context)).isEqualTo(value);
        } catch (IllegalArgumentException refused) {
            assertThat(refused).hasMessageContaining("unpaired surrogate");
        }
    }
}
