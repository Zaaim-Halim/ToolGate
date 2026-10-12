package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.BooleanValue;
import io.toolgate.api.ListValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.Binding;
import io.toolgate.spi.SessionState;
import io.toolgate.validate.OutputPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Fingerprints of trusted field values, so a session keeps neither customer IDs nor email addresses,
 * only what a bound argument is compared with. Values match exactly: a text never matches a number,
 * case counts, and numbers compare by value, so {@code 7} and {@code 7.0} are one value.
 */
public final class BoundValues {

    private BoundValues() {}

    /**
     * The fingerprints of a tool output's trusted fields.
     *
     * @param tool    the tool that ran
     * @param trusted the tool's trusted field paths
     * @param output  what it returned
     * @return fingerprints by {@link Binding#sourceKey}, at most {@link SessionState#MAX_TRUSTED_VALUES} per
     *         field, the first ones in the output's order; fields the output lacks, or whose values are not
     *         text, numbers or booleans, are left out
     */
    public static Map<String, Set<String>> of(ToolId tool, Set<String> trusted, ArgValue output) {
        var fingerprints = new HashMap<String, Set<String>>();
        for (String field : trusted) {
            Optional<List<ArgValue>> values = OutputPath.parse(field).select(output);
            if (values.isEmpty()) {
                continue;
            }
            String key = Binding.sourceKey(tool, field);
            var found = new LinkedHashSet<String>();
            for (ArgValue value : values.get()) {
                if (found.size() >= SessionState.MAX_TRUSTED_VALUES) {
                    break;
                }
                fingerprint(key, value).ifPresent(found::add);
            }
            if (!found.isEmpty()) {
                fingerprints.put(key, found);
            }
        }
        return fingerprints;
    }

    /**
     * Whether an argument takes only values the session trusts for a binding.
     *
     * @param binding the binding
     * @param value   the argument's value; for a list, every element must be trusted
     * @param trusts  whether the session trusts a fingerprint under the binding's source key
     * @return {@code true} if every value is trusted; {@code false} for an empty list or another shape
     */
    public static boolean bound(Binding binding, ArgValue value, Predicate<String> trusts) {
        if (value instanceof ListValue(List<ArgValue> elements)) {
            return !elements.isEmpty() && elements.stream().allMatch(element -> scalarBound(binding, element, trusts));
        }
        return scalarBound(binding, value, trusts);
    }

    private static boolean scalarBound(Binding binding, ArgValue value, Predicate<String> trusts) {
        return fingerprint(binding.sourceKey(), value).filter(trusts).isPresent();
    }

    /** A value's fingerprint under a field, so equal values in two fields do not share one. */
    static Optional<String> fingerprint(String key, ArgValue value) {
        String canonical = switch (value) {
            case TextValue(String text) -> "t:" + text;
            case NumberValue(BigDecimal number) -> "n:" + canonical(number);
            case BooleanValue(boolean flag) -> "b:" + flag;
            default -> null;
        };
        return canonical == null ? Optional.empty() : Optional.of(sha256(key + "\0" + canonical));
    }

    private static String canonical(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java runtime has SHA-256", missing);
        }
    }
}
