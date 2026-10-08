package io.toolgate.api;

/** A JSON {@code null} argument, kept explicit so that a missing value and a null value differ. */
public enum NullValue implements ArgValue {
    /** The only null value. */
    INSTANCE
}
