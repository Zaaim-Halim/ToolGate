package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requirePositive;

/**
 * Limits on one session of an agent. Exceeding a limit ends the run.
 *
 * @param toolCalls tool calls the session may make; denied calls do not count
 */
public record Budget(int toolCalls) {

    /** A budget with no limit. */
    public static final Budget UNLIMITED = new Budget(Integer.MAX_VALUE);

    /** Requires positive limits; a zero budget would end every run at its first call. */
    public Budget {
        requirePositive(toolCalls, "toolCalls");
    }
}
