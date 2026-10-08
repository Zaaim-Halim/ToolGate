package io.toolgate.spi;

/** Where a deferred call stands. Every status but {@link #PENDING} is final. */
public enum ApprovalStatus {
    /** Waiting for a reviewer. */
    PENDING,
    /** Approved, re-checked and handed to the application to run, once. */
    RELEASED,
    /** Approved, but the re-check failed: the policy, the principal's roles or the session changed. */
    REFUSED,
    /** A reviewer rejected it. */
    REJECTED,
    /** Nobody decided in time. */
    EXPIRED
}
