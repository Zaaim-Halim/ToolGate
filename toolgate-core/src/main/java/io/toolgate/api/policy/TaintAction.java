package io.toolgate.api.policy;

/** What a taint rule does to a call it objects to, unless the agent has a downgrade for the tool. */
public enum TaintAction {
    /** Deny the call. The default for headless agents. */
    BLOCK,
    /** Hold the call for review: by the user in interactive agents, by the review queue otherwise. */
    DEFER
}
