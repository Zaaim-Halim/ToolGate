package io.toolgate.springai;

import static java.util.Objects.requireNonNull;

import io.toolgate.api.ToolId;
import java.time.Instant;

/**
 * A tool call of one chat request that ToolGate deferred for review, for the application's own screen,
 * such as a "Confirm the refund?" prompt in an interactive agent.
 *
 * @param approvalId what to pass to {@link ToolGateApprovals#approve}, {@link ToolGateApprovals#reject}
 *                   or {@link ToolGateApprovals#cancel}
 * @param tool       the tool the model asked for
 * @param expiresAt  when the call expires if nobody decides; reckoned when it was deferred, so it can
 *                   be a few milliseconds early
 */
public record DeferredAction(String approvalId, ToolId tool, Instant expiresAt) {

    /** Requires every component. */
    public DeferredAction {
        requireNonNull(approvalId, "approvalId");
        requireNonNull(tool, "tool");
        requireNonNull(expiresAt, "expiresAt");
    }
}
