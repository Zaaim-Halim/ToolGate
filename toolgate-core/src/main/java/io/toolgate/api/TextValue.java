package io.toolgate.api;

import java.util.Objects;

/**
 * A JSON string argument.
 *
 * @param value the string, exactly as the adapter parsed it
 */
public record TextValue(String value) implements ArgValue {

    /** Rejects a Java {@code null}; JSON null is {@link NullValue}. */
    public TextValue {
        Objects.requireNonNull(value, "value");
    }
}
