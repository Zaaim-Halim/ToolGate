package io.toolgate.spi;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.queue.InMemoryApprovalQueue;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Stores deferred calls until a reviewer decides, or they expire.
 *
 * <p>Status changes are compare-and-set, so a call is released at most once: when two reviewers,
 * or one reviewer clicking twice, approve the same call, exactly one change succeeds. Stores shared
 * across instances, such as a JDBC table, implement the same contract with a conditional update.
 */
public interface ApprovalQueue {

    /**
     * A queue in this JVM's memory, the gate's default: for one instance, or to share between gates in
     * tests, such as a gate redeployed with a new policy.
     *
     * @return a new, empty queue
     */
    static ApprovalQueue inMemory() {
        return new InMemoryApprovalQueue();
    }

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
     * Replaces a stored call with its next version, if it is still at the version the caller read: a
     * compare-and-set, so of any number of concurrent decisions, claims or outcomes on any instance,
     * exactly one wins. The new version's decisions must begin with the stored ones, since decisions
     * are only ever added.
     *
     * @param expected the call as the caller read it
     * @param next     the call to store; its version must be one more than {@code expected}'s
     * @return {@code true} if replaced, {@code false} if the call changed meanwhile or is unknown
     * @throws IllegalArgumentException if {@code next} is not the next version of {@code expected}
     */
    boolean replace(PendingCall expected, PendingCall next);

    /**
     * Calls in a status, oldest first, such as {@link ApprovalStatus#RELEASED} ones waiting for an
     * executor, or {@link ApprovalStatus#EXECUTING} ones whose lease may have run out.
     *
     * @param status the status
     * @return the calls
     */
    List<PendingCall> withStatus(ApprovalStatus status);

    /**
     * A page of calls in some statuses, deferred at or after a time, newest first: a history screen's
     * search, bounded so it never loads a whole queue. The default reads {@link #withStatus} for each
     * status; a store with a database should override it with one bounded query.
     *
     * @param statuses      the statuses
     * @param deferredSince only calls deferred at or after this time
     * @param limit         at most this many calls
     * @param offset        how many matching calls to skip, for the next page
     * @return the calls, newest first
     */
    default List<PendingCall> find(Set<ApprovalStatus> statuses, Instant deferredSince, int limit, int offset) {
        return statuses.stream().flatMap(status -> withStatus(status).stream())
                .filter(call -> !call.deferredAt().isBefore(deferredSince))
                .sorted(Comparator.comparing(PendingCall::deferredAt).thenComparing(PendingCall::approvalId).reversed())
                .skip(offset).limit(limit).toList();
    }

    /**
     * How many decisions made under an access the queue holds in {@code (after, upTo]}, across every
     * instance that shares it. The gate counts super admin decisions this way, so a burst spread over
     * several instances still raises {@code toolgate.superadmin.burst}. The default cannot count, and the
     * gate then counts only the decisions it made itself; a shared store should override it.
     *
     * @param access the access the decisions were made under, such as {@link ApprovalAccess#SUPER_ADMIN}
     * @param after  only decisions made after this instant
     * @param upTo   only decisions made at or before this instant
     * @return the count, or empty if this queue cannot count decisions
     */
    default OptionalLong countDecisions(ApprovalAccess access, Instant after, Instant upTo) {
        return OptionalLong.empty();
    }

    /**
     * Calls still waiting for a decision, oldest first, including any past their expiry that have
     * not been expired yet.
     *
     * @return the pending calls
     */
    default List<PendingCall> pending() {
        return withStatus(ApprovalStatus.PENDING);
    }

    /**
     * Checks that {@code next} may replace {@code expected}, for implementations of {@link #replace}.
     *
     * @param expected the call as the caller read it
     * @param next     the call to store
     * @throws IllegalArgumentException if {@code next} is not the next version of {@code expected}
     */
    static void requireNextVersion(PendingCall expected, PendingCall next) {
        if (!next.approvalId().equals(expected.approvalId())) {
            throw new IllegalArgumentException("cannot replace " + expected.approvalId() + " with " + next.approvalId());
        }
        if (next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("version " + next.version() + " does not follow " + expected.version());
        }
        if (next.decisions().size() < expected.decisions().size()
                || !next.decisions().subList(0, expected.decisions().size()).equals(expected.decisions())) {
            throw new IllegalArgumentException("decisions are only ever added, never changed or removed");
        }
    }
}
