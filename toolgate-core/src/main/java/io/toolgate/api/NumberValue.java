package io.toolgate.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A JSON number argument.
 *
 * <p>Trailing zeros are stripped, so {@code 1}, {@code 1.0} and {@code 1.00} are equal and hash the
 * same: a rule must not behave differently because the model wrote a number another way.
 *
 * @param value the number, with trailing zeros removed
 */
public record NumberValue(BigDecimal value) implements ArgValue {

    /** Normalizes the scale so that equal numbers are equal records. */
    public NumberValue {
        Objects.requireNonNull(value, "value");
        value = value.stripTrailingZeros();
    }
}
