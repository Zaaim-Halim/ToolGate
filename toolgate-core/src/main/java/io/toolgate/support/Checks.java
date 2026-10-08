package io.toolgate.support;

import java.util.Objects;

/** Argument checks shared by the records in core, so every type fails fast with the same message. */
public final class Checks {

    private Checks() {}

    /** Returns {@code value} if it has visible content, otherwise throws naming the argument. */
    public static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Returns {@code value} if it is at least one, otherwise throws naming the argument. */
    public static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, was " + value);
        }
        return value;
    }
}
