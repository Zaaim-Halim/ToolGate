package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.ToolCall;
import java.time.Instant;
import java.util.Objects;

/**
 * A deferred call waiting in, or decided by, the approval queue.
 *
 * <p>It holds the real argument values, because a reviewer must see them. A queue that stores
 * pending calls outside memory must protect them like the data they carry.
 *
 * @param approvalId the call's ID in the queue
 * @param call       the call as the model made it
 * @param deferral   the decision that deferred it, which names the rule the approval covers
 * @param deferredAt when it was deferred
 * @param expiresAt  when it expires if nobody decides
 * @param status     where it stands
 */
public record PendingCall(String approvalId, ToolCall call, Decision.Defer deferral, Instant deferredAt,
                          Instant expiresAt, ApprovalStatus status) {

    /** Requires every component and an expiry after the deferral. */
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
    }

    /**
     * The same call with another status.
     *
     * @param newStatus the status
     * @return the updated call
     */
    public PendingCall withStatus(ApprovalStatus newStatus) {
        return new PendingCall(approvalId, call, deferral, deferredAt, expiresAt, newStatus);
    }
}
