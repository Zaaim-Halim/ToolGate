package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requirePositive;

/**
 * Limits on one session of an agent. Exceeding a limit ends the run.
 *
 * <p>Known limit: budgets count per session and never reset. For a chat agent the session is
 * usually the whole conversation, so once a budget ends a run, every later call in that
 * conversation ends too. Counting per request or per agent loop is planned with session scopes.
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
