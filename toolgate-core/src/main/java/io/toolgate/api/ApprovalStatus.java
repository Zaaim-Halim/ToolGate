package io.toolgate.api;

/**
 * Where a deferred call stands.
 *
 * <pre>
 * PENDING ─► RELEASED ─► EXECUTING ─► EXECUTED | FAILED | UNKNOWN
 *    └─► REFUSED | REJECTED | EXPIRED | CANCELLED
 * </pre>
 *
 * {@link #PENDING}, {@link #RELEASED}, {@link #EXECUTING} and {@link #UNKNOWN} still change; the rest
 * are final.
 */
public enum ApprovalStatus {
    /** Waiting for a reviewer, or for a second person to confirm a super admin's approval. */
    PENDING,
    /** Approved and re-checked: ready to run, once, by whichever executor claims it. */
    RELEASED,
    /** Claimed by an executor, which is running the tool under a lease. */
    EXECUTING,
    /** The tool ran and returned. */
    EXECUTED,
    /** The tool ran and failed. */
    FAILED,
    /**
     * The executor's lease ran out with no outcome: the tool may or may not have run. Never retried
     * automatically; a person checks the real system and resolves it.
     */
    UNKNOWN,
    /** Approved, but the re-check failed: the policy, the principal's roles or the session changed. */
    REFUSED,
    /** A reviewer rejected it. */
    REJECTED,
    /** Nobody decided in time. */
    EXPIRED,
    /** Withdrawn before anyone decided, by its requester or a super admin. */
    CANCELLED
}
