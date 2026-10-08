package io.toolgate.api;

/** How strictly a policy, or a single rule within it, is applied. */
public enum Mode {
    /** No checks; calls pass straight through. A policy-wide emergency switch, never set per rule. */
    OFF,
    /** Every decision is computed and recorded, but nothing is blocked. Used while adopting a policy. */
    MONITOR,
    /** Decisions are applied. */
    ENFORCE
}
