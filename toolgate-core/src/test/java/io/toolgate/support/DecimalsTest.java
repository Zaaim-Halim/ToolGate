package io.toolgate.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DecimalsTest {

    @Test
    void ordinaryNumbersHavePlainText() {
        assertThat(Decimals.plainText(new BigDecimal("480.50"))).contains("480.50");
        assertThat(Decimals.plainText(new BigDecimal("1E+3"))).contains("1000");
        assertThat(Decimals.plainText(new BigDecimal("-0.001"))).contains("-0.001");
    }

    @Test
    void hugeAndTinyNumbersHaveNoneButAreStillDescribed() {
        BigDecimal huge = new BigDecimal("1e999999999");
        BigDecimal tiny = new BigDecimal("1e-999999999");

        assertThat(Decimals.plainText(huge)).isEmpty();
        assertThat(Decimals.plainText(tiny)).isEmpty();
        assertThat(Decimals.describe(huge)).isEqualTo("1E+999999999");
        assertThat(Decimals.describe(tiny)).isEqualTo("1E-999999999");
    }

    @Test
    void theLimitIsExact() {
        BigDecimal largest = BigDecimal.TEN.pow(Decimals.MAX_PLAIN_DIGITS - 1);
        BigDecimal tooLarge = BigDecimal.TEN.pow(Decimals.MAX_PLAIN_DIGITS);
        BigDecimal finest = BigDecimal.ONE.movePointLeft(Decimals.MAX_PLAIN_DIGITS);
        BigDecimal tooFine = BigDecimal.ONE.movePointLeft(Decimals.MAX_PLAIN_DIGITS + 1);

        assertThat(Decimals.plainText(largest)).hasValueSatisfying(text -> assertThat(text).hasSize(Decimals.MAX_PLAIN_DIGITS));
        assertThat(Decimals.plainText(tooLarge)).isEmpty();
        assertThat(Decimals.plainText(finest)).isPresent();
        assertThat(Decimals.plainText(tooFine)).isEmpty();
    }
}
