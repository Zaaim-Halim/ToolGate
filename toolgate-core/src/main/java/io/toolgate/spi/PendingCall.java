package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Execution;
import io.toolgate.api.ToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A deferred call in the approval queue, with everything decided about it so far.
 *
 * <p>It holds the real argument values, because a reviewer must see them. A queue that stores
 * pending calls outside memory must protect them like the data they carry.
 *
 * @param approvalId the call's ID in the queue, as {@link Decision.Defer#approvalId()} gives it
 * @param call       the call as the model made it
 * @param deferral   the decision that deferred it, which names the rule the approval covers
 * @param deferredAt when it was deferred
 * @param expiresAt  when it expires if nobody decides
 * @param status     where it stands
 * @param version    how many times it changed since it was queued; the queue replaces a call only
 *                   at the version the caller read, so concurrent decisions cannot both win
 * @param decisions  what people decided about it, oldest first
 * @param execution  the executor's run once claimed; null before
 */
public record PendingCall(String approvalId, ToolCall call, Decision.Defer deferral, Instant deferredAt,
                          Instant expiresAt, ApprovalStatus status, long version, List<ApprovalDecision> decisions,
                          Execution execution) {

    /** Requires every component but the execution, an expiry after the deferral, and copies the decisions. */
    public PendingCall {
        requireNonBlank(approvalId, "approvalId");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(deferral, "deferral");
        Objects.requireNonNull(deferredAt, "deferredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if (!expiresAt.isAfter(deferredAt)) {
            throw new IllegalArgumentException("a pending call must expire after it was deferred");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        decisions = List.copyOf(decisions);
    }

    /**
     * A newly deferred call: version 0, nothing decided, not run.
     *
     * @param approvalId the call's ID in the queue
     * @param call       the call as the model made it
     * @param deferral   the decision that deferred it
     * @param deferredAt when it was deferred
     * @param expiresAt  when it expires if nobody decides
     * @param status     where it stands; a queue accepts only {@link ApprovalStatus#PENDING}
     */
    public PendingCall(String approvalId, ToolCall call, Decision.Defer deferral, Instant deferredAt,
                       Instant expiresAt, ApprovalStatus status) {
        this(approvalId, call, deferral, deferredAt, expiresAt, status, 0, List.of(), null);
    }

    /**
     * The executor's run, if the call was claimed.
     *
     * @return the run, or empty before a claim
     */
    public Optional<Execution> run() {
        return Optional.ofNullable(execution);
    }

    /**
     * The next version of this call, with another status.
     *
     * @param newStatus the status
     * @return the updated call
     */
    public PendingCall withStatus(ApprovalStatus newStatus) {
        return new PendingCall(approvalId, call, deferral, deferredAt, expiresAt, newStatus, version + 1, decisions,
                execution);
    }

    /**
     * The next version of this call, with one more decision and a status.
     *
     * @param decision  the decision
     * @param newStatus the status it leads to
     * @return the updated call
     */
    public PendingCall withDecision(ApprovalDecision decision, ApprovalStatus newStatus) {
        var more = new ArrayList<>(decisions);
        more.add(Objects.requireNonNull(decision, "decision"));
        return new PendingCall(approvalId, call, deferral, deferredAt, expiresAt, newStatus, version + 1, more, execution);
    }

    /**
     * The next version of this call, with its run and a status.
     *
     * @param run       the run
     * @param newStatus the status it leads to
     * @return the updated call
     */
    public PendingCall withExecution(Execution run, ApprovalStatus newStatus) {
        return new PendingCall(approvalId, call, deferral, deferredAt, expiresAt, newStatus, version + 1, decisions,
                Objects.requireNonNull(run, "run"));
    }
}
