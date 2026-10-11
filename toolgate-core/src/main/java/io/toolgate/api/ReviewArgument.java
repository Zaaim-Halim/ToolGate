package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.List;
import java.util.Objects;

/**
 * One argument of a deferred call as a review screen may show it. The display text came from the
 * model, so a screen must render it as plain text, never as markup.
 *
 * @param name       the argument's name
 * @param display    what to show: the value, its masked form, or nothing when hidden
 * @param visibility how much of the value it shows
 * @param warnings   what looks suspicious about the value, such as characters that make one domain look
 *                   like another; empty when nothing does
 */
public record ReviewArgument(String name, String display, Visibility visibility, List<String> warnings) {

    /** How much of a value a review screen shows. */
    public enum Visibility {
        /** All of it. */
        SHOWN,
        /** A masked form, such as the last 4 characters. */
        MASKED,
        /** Nothing: only that the argument exists. */
        HIDDEN
    }

    /** Requires every component and copies the warnings. */
    public ReviewArgument {
        requireNonBlank(name, "name");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(visibility, "visibility");
        warnings = List.copyOf(warnings);
    }
}
