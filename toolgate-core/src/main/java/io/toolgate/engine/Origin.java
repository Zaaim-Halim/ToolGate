package io.toolgate.engine;

/**
 * Why an evaluator stopped a call, which decides whether a configured downgrade may soften it.
 *
 * <p>A taint rule objects to where the session has been, which a safe tool fixes: a draft cannot
 * leak. An access rule objects to the call itself, such as an outside recipient, which a draft
 * would still carry; it is never softened.
 */
public enum Origin {
    /** The tool, its arguments or the principal's rights. */
    ACCESS,
    /** The session's taint. */
    TAINT
}
