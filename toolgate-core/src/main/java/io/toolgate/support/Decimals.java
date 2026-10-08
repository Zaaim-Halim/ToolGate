package io.toolgate.support;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plain decimal text, read one way only. Policy limits and argument values use the same reading, so a
 * value can never compare differently from what its author meant: {@code 1e3}, {@code 480,00},
 * {@code +5} and surrounding spaces are not plain decimals.
 */
public final class Decimals {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    private Decimals() {}

    /** The number, if {@code text} is an optional minus, digits and an optional fraction. */
    public static Optional<BigDecimal> parsePlain(String text) {
        return text != null && PLAIN_DECIMAL.matcher(text).matches() ? Optional.of(new BigDecimal(text)) : Optional.empty();
    }
}
