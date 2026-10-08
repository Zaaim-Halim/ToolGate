package io.toolgate.api;

/**
 * Whether a tool's action can be undone. A tool without a stated effect is treated as
 * {@link #IRREVERSIBLE}, so a forgotten label fails safe.
 */
public enum Effect {
    /** Reads only. */
    READ,
    /** Changes something that can be undone, such as saving a draft. */
    REVERSIBLE,
    /** Changes something that cannot be undone, such as sending an email or moving money. */
    IRREVERSIBLE
}
