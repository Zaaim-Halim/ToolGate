package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Sums by rule ID, kept sorted so equal totals compare and serialize alike. */
final class Totals {

    private Totals() {}

    /** A sorted, unmodifiable copy; amounts are normalized and must not be negative. */
    static Map<String, BigDecimal> copy(Map<String, BigDecimal> amounts) {
        var sorted = new TreeMap<String, BigDecimal>();
        amounts.forEach((ruleId, amount) -> {
            requireNonBlank(ruleId, "rule ID");
            if (Objects.requireNonNull(amount, "amount").signum() < 0) {
                throw new IllegalArgumentException("total for " + ruleId + " must not be negative, was " + amount);
            }
            sorted.put(ruleId, amount.stripTrailingZeros());
        });
        return sorted.isEmpty() ? Map.of() : Collections.unmodifiableMap(sorted);
    }

    static Map<String, BigDecimal> add(Map<String, BigDecimal> totals, Map<String, BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return totals;
        }
        var sum = new TreeMap<>(totals);
        amounts.forEach((ruleId, amount) -> sum.merge(ruleId, amount, BigDecimal::add));
        return copy(sum);
    }
}
