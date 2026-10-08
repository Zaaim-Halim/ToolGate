package io.toolgate.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A JSON object argument, and the type of a tool call's whole argument list.
 *
 * <p>Field order is kept for readable audit output; equality ignores order. Duplicate keys cannot
 * be represented, so adapters must reject them while parsing rather than pick one.
 *
 * @param fields the fields by name; unmodifiable, never containing Java {@code null}
 */
public record ObjectValue(Map<String, ArgValue> fields) implements ArgValue {

    private static final ObjectValue EMPTY = new ObjectValue(Map.of());

    /** Copies the fields into an unmodifiable map that keeps their order. */
    public ObjectValue {
        var copy = new LinkedHashMap<String, ArgValue>();
        fields.forEach((name, value) -> copy.put(
                Objects.requireNonNull(name, "field name"),
                Objects.requireNonNull(value, () -> "value of field " + name)));
        fields = Collections.unmodifiableMap(copy);
    }

    /** An object with no fields, for tools that take no arguments. */
    public static ObjectValue empty() {
        return EMPTY;
    }

    /** An object with the given fields, in the map's iteration order. */
    public static ObjectValue of(Map<String, ? extends ArgValue> fields) {
        return new ObjectValue(new LinkedHashMap<String, ArgValue>(fields));
    }

    /** The value of one field, if present. */
    public Optional<ArgValue> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }
}
