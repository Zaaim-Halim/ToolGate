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

    /**
     * A JSON string.
     *
     * @param value the string
     * @return the argument value
     */
    static TextValue text(String value) {
        return new TextValue(value);
    }

    /**
     * A JSON number.
     *
     * @param value the number
     * @return the argument value, with trailing zeros removed
     */
    static NumberValue number(BigDecimal value) {
        return new NumberValue(value);
    }

    /**
     * A JSON number from an integral value.
     *
     * @param value the number
     * @return the argument value
     */
    static NumberValue number(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    /**
     * A JSON boolean.
     *
     * @param value the boolean
     * @return the argument value
     */
    static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    /**
     * JSON {@code null}.
     *
     * @return the only null value
     */
    static NullValue nullValue() {
        return NullValue.INSTANCE;
    }

    /**
     * A JSON array.
     *
     * @param elements the elements in order
     * @return the argument value, holding a copy of the elements
     */
    static ListValue list(List<? extends ArgValue> elements) {
        return new ListValue(List.copyOf(elements));
    }

    /**
     * A JSON object; field order is kept.
     *
     * @param fields the fields by name
     * @return the argument value, holding a copy of the fields
     */
    static ObjectValue object(Map<String, ? extends ArgValue> fields) {
        return ObjectValue.of(fields);
    }
}
