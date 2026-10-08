package io.toolgate.api;

import java.util.List;

/**
 * A JSON array argument.
 *
 * @param elements the elements in order; never contains Java {@code null}
 */
public record ListValue(List<ArgValue> elements) implements ArgValue {

    /** Copies the elements, so later changes to the caller's list cannot change a checked call. */
    public ListValue {
        elements = List.copyOf(elements);
    }
}
