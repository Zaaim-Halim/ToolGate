package io.toolgate.spi;

import io.toolgate.api.ApprovalStatus;
import java.util.List;
import java.util.Optional;

/**
 * Stores deferred calls until a reviewer decides, or they expire.
 *
 * <p>Status changes are compare-and-set, so a call is released at most once: when two reviewers,
 * or one reviewer clicking twice, approve the same call, exactly one change succeeds. Stores shared
 * across instances, such as a JDBC table, implement the same contract with a conditional update.
 */
public interface ApprovalQueue {

    /**
     * Stores a newly deferred call. Adding a call whose approval ID is already stored does nothing,
     * so a retried call is not queued twice.
     *
     * @param call a call with status {@link ApprovalStatus#PENDING}
     */
    void add(PendingCall call);

    /**
     * One stored call.
     *
     * @param approvalId the call's approval ID
     * @return the call, or empty if no call has that ID
     */
    Optional<PendingCall> get(String approvalId);

    /**
     * Changes a call's status if it is still {@code from}.
     *
     * @param approvalId the call's approval ID
     * @param from       the status the caller saw
     * @param to         the new status
     * @return {@code true} if changed, {@code false} if the status had changed meanwhile, or the call is unknown
     */
    boolean transition(String approvalId, ApprovalStatus from, ApprovalStatus to);

    /**
     * Calls still waiting for a decision, oldest first, including any past their expiry that have
     * not been expired yet.
     *
     * @return the pending calls
     */
    List<PendingCall> pending();
}
