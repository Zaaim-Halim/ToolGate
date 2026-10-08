package io.toolgate.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One tool argument value, as a small JSON-like tree.
 *
 * <p>Adapters build these from the framework's own JSON parser, so the core needs no JSON library.
 * The hierarchy is sealed so that validators switch over it exhaustively and hashing stays
 * deterministic; JSON {@code null} is the explicit {@link NullValue}, never a Java {@code null}.
 */
public sealed interface ArgValue permits TextValue, NumberValue, BooleanValue, NullValue, ListValue, ObjectValue {

    /** A JSON string. */
    static TextValue text(String value) {
        return new TextValue(value);
    }

    /** A JSON number. */
    static NumberValue number(BigDecimal value) {
        return new NumberValue(value);
    }

    /** A JSON number from an integral value. */
    static NumberValue number(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    /** A JSON boolean. */
    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    /** JSON {@code null}. */
    static NullValue nullValue() {
        return NullValue.INSTANCE;
    }

    /** A JSON array. */
    static ListValue list(List<? extends ArgValue> elements) {
        return new ListValue(List.copyOf(elements));
    }

    /** A JSON object; field order is kept. */
    static ObjectValue object(Map<String, ? extends ArgValue> fields) {
        return ObjectValue.of(fields);
    }
}
