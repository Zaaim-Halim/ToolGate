package io.toolgate.api;

/**
 * A JSON boolean argument.
 *
 * @param value the boolean
 */
public record BooleanValue(boolean value) implements ArgValue {}
