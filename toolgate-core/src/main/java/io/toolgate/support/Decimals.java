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

    /**
     * Digits on either side of the point beyond which a number has no plain text. Far beyond any
     * real ID or amount, and far below the billion characters that {@code 1e999999999} would need.
     */
    static final int MAX_PLAIN_DIGITS = 100;

    private Decimals() {}

    /**
     * The plain text of a number, if it has a reasonable one. A model can send {@code 1e999999999},
     * which {@link BigDecimal} holds in a few bytes but whose plain text is a billion characters:
     * converting it would exhaust memory, so such a number has no plain text here.
     *
     * @param number a number
     * @return its plain text, or empty if it has more than {@value #MAX_PLAIN_DIGITS} digits on
     *         either side of the point
     */
    public static Optional<String> plainText(BigDecimal number) {
        long integerDigits = (long) number.precision() - number.scale();
        if (integerDigits > MAX_PLAIN_DIGITS || number.scale() > MAX_PLAIN_DIGITS) {
            return Optional.empty();
        }
        return Optional.of(number.toPlainString());
    }

    /**
     * A short description of any number, for reasons and logs: plain text when it has one,
     * scientific notation otherwise.
     *
     * @param number a number
     * @return its description, never longer than a few hundred characters
     */
    public static String describe(BigDecimal number) {
        return plainText(number).orElseGet(number::toString);
    }

    /** The number, if {@code text} is an optional minus, digits and an optional fraction. */
    public static Optional<BigDecimal> parsePlain(String text) {
        return text != null && PLAIN_DECIMAL.matcher(text).matches() ? Optional.of(new BigDecimal(text)) : Optional.empty();
    }
}
