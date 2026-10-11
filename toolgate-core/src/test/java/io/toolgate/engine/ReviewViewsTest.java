package io.toolgate.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.Mask;
import org.junit.jupiter.api.Test;

/** What a reviewer is warned about, and how masks show values. */
class ReviewViewsTest {

    @Test
    void plainAsciiNeedsNoWarning() {
        assertThat(ReviewViews.warnings("billing@mycompany.com")).isEmpty();
    }

    /** "paypal.com" with a Cyrillic "а" reads the same and goes somewhere else. */
    @Test
    void lookalikeLettersAreFlagged() {
        assertThat(ReviewViews.warnings("billing@pаypal.com")).singleElement().asString().contains("non-ASCII");
    }

    /** A right-to-left override can make "moc.live@" display as "@evil.com" backwards. */
    @Test
    void invisibleAndDirectionCharactersAreFlagged() {
        assertThat(ReviewViews.warnings("invoice‮fdp.exe")).anySatisfy(warning -> assertThat(warning).contains("invisible"));
        assertThat(ReviewViews.warnings("pay​pal.com")).anySatisfy(warning -> assertThat(warning).contains("invisible"));
        assertThat(ReviewViews.warnings("line\u0007bell")).anySatisfy(warning -> assertThat(warning).contains("control"));
    }

    @Test
    void masksShowOnlyTheirTailAndNeverAWholeShortValue() {
        assertThat(Mask.last(4).apply("DE89 3704 0044 0532 0130 00")).isEqualTo("•••• 0 00");
        assertThat(Mask.last(4).apply("abc")).isEqualTo("••••");
        assertThat(Mask.full().apply("secret")).isEqualTo("••••");
    }
}
