package io.toolgate.spi;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;

/**
 * Hears what happens to deferred calls, such as to notify reviewers that a call is waiting, page
 * someone on a burst of super admin decisions, or tell a user their approved action ran.
 *
 * <p>A listener is an {@link AuditSink} underneath, through {@link #asSink()}: it runs off the request
 * path, on the audit thread, and a failing listener never changes a decision. Its events carry no
 * argument values, so they are safe to forward to chat, email or a webhook; a notification should link
 * to the review screen rather than describe the call.
 */
public interface ApprovalListener {

    /**
     * A call was deferred and waits for review.
     *
     * @param event the decision; its {@link AuditEvent.Decided#applied()} is a {@link Decision.Defer},
     *              whose approval ID identifies the call
     */
    default void deferred(AuditEvent.Decided event) {}

    /**
     * Someone or something decided a call: approved and released, refused on re-check, rejected,
     * cancelled or expired.
     *
     * @param event the decision
     */
    default void decided(AuditEvent.ApprovalDecided event) {}

    /**
     * A super admin's approval was recorded, and the call waits for a second person.
     *
     * @param event the recorded approval
     */
    default void recorded(AuditEvent.ApprovalRecorded event) {}

    /**
     * An approved call's run ended: {@link ApprovalStatus#EXECUTED} or {@link ApprovalStatus#FAILED},
     * reported by its executor or by a person resolving it, or {@link ApprovalStatus#UNKNOWN} when its
     * outcome was lost.
     *
     * @param event the outcome
     */
    default void finished(AuditEvent.ApprovalDecided event) {}

    /**
     * A super admin or an auditor opened a deferred call, which may be another tenant's.
     *
     * @param event the view
     */
    default void viewed(AuditEvent.ApprovalViewed event) {}

    /**
     * A super admin or an auditor listed the deferred calls they may see, across tenants.
     *
     * @param event the listing
     */
    default void listed(AuditEvent.ApprovalsListed event) {}

    /**
     * Super admins made more decisions in a short time than is normal.
     *
     * @param event the burst
     */
    default void superAdminBurst(AuditEvent.SuperAdminBurst event) {}

    /**
     * This listener as an audit sink, which is how a gate delivers to it.
     *
     * @return a sink that hands each approval event to the matching method and ignores the rest
     */
    default AuditSink asSink() {
        return event -> {
            switch (event) {
                case AuditEvent.Decided decided when decided.applied() instanceof Decision.Defer -> deferred(decided);
                case AuditEvent.ApprovalDecided decided when isOutcome(decided.status()) -> finished(decided);
                case AuditEvent.ApprovalDecided decided -> decided(decided);
                case AuditEvent.ApprovalRecorded recorded -> recorded(recorded);
                case AuditEvent.SuperAdminBurst burst -> superAdminBurst(burst);
                case AuditEvent.ApprovalViewed viewedCall -> viewed(viewedCall);
                case AuditEvent.ApprovalsListed listing -> listed(listing);
                default -> {
                    // Not about approvals.
                }
            }
        };
    }

    private static boolean isOutcome(ApprovalStatus status) {
        return status == ApprovalStatus.EXECUTED || status == ApprovalStatus.FAILED || status == ApprovalStatus.UNKNOWN;
    }
}
