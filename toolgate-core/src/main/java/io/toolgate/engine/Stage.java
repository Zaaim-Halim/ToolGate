package io.toolgate.engine;

/**
 * The order evaluators run in. Earlier stages are cheaper or more fundamental, so an enforced
 * deny there spares the later ones.
 */
public enum Stage {
    /**
     * Run-ending limits come first: once a session has used up its denial budget, its next call
     * must end the run whatever else is wrong with it, or a prober would get denials forever.
     */
    LIMITS,
    /** Which agent and which tool, and whether that tool may be called at all. */
    IDENTIFY,
    /** The shape of the arguments. */
    STRUCTURE,
    /** The values of the arguments. */
    ARGUMENTS,
    /** Whether the principal may touch the records named in the arguments. */
    OWNERSHIP,
    /** What the session has done so far: taint and rates. */
    SESSION
}
