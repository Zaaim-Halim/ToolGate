package io.toolgate.api.policy;

/** What a numeric limit does to a call that breaks it. */
public enum OnViolation {
    /** Deny the call. The default. */
    DENY,
    /** Hold the call for review; an approval covers the limit when the call is re-checked. */
    DEFER
}
