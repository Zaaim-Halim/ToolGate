package io.toolgate.validate;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ListValue;
import io.toolgate.api.ObjectValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A path to fields in a tool's output, in a deliberately small language: field names separated by
 * dots, and {@code [*]} for every element of an array. Examples: {@code customer.id},
 * {@code items[*].tenantId}, {@code [*].owner} for an output that is itself an array.
 *
 * <p>Full JSONPath has filters, recursion and slices, each a chance for a rule to match less than
 * its author thinks. This language has nothing to misread. Field names are letters, digits,
 * {@code _} and {@code -}, starting with a letter or {@code _}.
 */
public final class OutputPath {

    /** One step of a path. */
    private sealed interface Step permits Field, Each {}

    private record Field(String name) implements Step {}

    private record Each() implements Step {}

    private static final Each EACH = new Each();
    private static final String EACH_TOKEN = "[*]";

    private final String text;
    private final List<Step> steps;

    private OutputPath(String text, List<Step> steps) {
        this.text = text;
        this.steps = List.copyOf(steps);
    }

    /**
     * Parses a path.
     *
     * @param text such as {@code items[*].tenantId}
     * @return the path
     * @throws IllegalArgumentException if the text is not a valid path
     */
    public static OutputPath parse(String text) {
        Objects.requireNonNull(text, "path");
        var steps = new ArrayList<Step>();
        int index = 0;
        boolean expectField = !text.startsWith(EACH_TOKEN);
        while (index < text.length()) {
            if (text.startsWith(EACH_TOKEN, index)) {
                steps.add(EACH);
                index += EACH_TOKEN.length();
                expectField = false;
                continue;
            }
            if (!expectField) {
                if (text.charAt(index) != '.') {
                    throw invalid(text, "expected '.' or '[*]' at position " + index);
                }
                index++;
            }
            int end = index;
            while (end < text.length() && isNameChar(text.charAt(end), end == index)) {
                end++;
            }
            if (end == index) {
                throw invalid(text, "expected a field name at position " + index);
            }
            steps.add(new Field(text.substring(index, end)));
            index = end;
            expectField = false;
        }
        if (steps.isEmpty()) {
            throw invalid(text, "a path needs at least one step");
        }
        return new OutputPath(text, steps);
    }

    /**
     * Every value the path reaches in an output.
     *
     * @param root the tool's output
     * @return the values, empty for an empty array along the way, or empty Optional when the
     *         output does not have the path's shape: a missing field, or a field or element of
     *         the wrong type
     */
    public Optional<List<ArgValue>> select(ArgValue root) {
        List<ArgValue> current = List.of(root);
        for (Step step : steps) {
            var next = new ArrayList<ArgValue>();
            for (ArgValue value : current) {
                switch (step) {
                    case Field(String name) -> {
                        if (!(value instanceof ObjectValue object) || object.field(name).isEmpty()) {
                            return Optional.empty();
                        }
                        next.add(object.field(name).get());
                    }
                    case Each each -> {
                        if (!(value instanceof ListValue(List<ArgValue> elements))) {
                            return Optional.empty();
                        }
                        next.addAll(elements);
                    }
                }
            }
            current = next;
        }
        return Optional.of(List.copyOf(current));
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutputPath path && path.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    private static boolean isNameChar(char c, boolean first) {
        boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        return first ? letter : letter || (c >= '0' && c <= '9') || c == '-';
    }

    private static IllegalArgumentException invalid(String text, String problem) {
        return new IllegalArgumentException("invalid output path '" + text + "': " + problem);
    }
}
