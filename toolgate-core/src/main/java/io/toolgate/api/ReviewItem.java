package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A deferred call as one viewer may see it on a review screen, from {@link ToolGate#reviewItem}: what
 * the action is, why it waits, where the session's taint came from, and its arguments as the policy's
 * review view shows them. Approving with its {@link #digest()} binds the approval to exactly this view.
 *
 * <p>Everything here but the deferral reason is data, and the arguments came from the model: a screen
 * renders all of it as plain text.
 *
 * @param approvalId     the call's approval ID
 * @param agentId        the agent that made it
 * @param tool           the tool it calls
 * @param title          what the screen calls the action
 * @param requester      the principal the call is for
 * @param status         where it stands
 * @param expiresAt      when it expires if nobody decides
 * @param deferralRule   the rule that deferred it
 * @param deferralReason why, in ToolGate's own words; it can quote an argument
 * @param arguments      the arguments, as the review view shows them
 * @param provenance     where the session's taint came from, when the call was viewed
 * @param decisions      what people decided so far, oldest first
 * @param access         why the viewer sees it
 * @param mayDecide      whether the viewer may take the step its status allows: approve or reject it while
 *                       {@link ApprovalStatus#PENDING}, resolve it while {@link ApprovalStatus#UNKNOWN}
 * @param digest         a hash of what this view shows; pass it to
 *                       {@link ToolGate#approve(String, Principal, Principal, String, String)}
 */
public record ReviewItem(String approvalId, String agentId, ToolId tool, String title, Principal requester,
                         ApprovalStatus status, Instant expiresAt, String deferralRule, String deferralReason,
                         List<ReviewArgument> arguments, List<Provenance> provenance, List<ApprovalDecision> decisions,
                         ApprovalAccess access, boolean mayDecide, String digest) {

    /** Requires every component and copies the lists. */
    public ReviewItem {
        requireNonBlank(approvalId, "approvalId");
        requireNonBlank(agentId, "agentId");
        Objects.requireNonNull(tool, "tool");
        requireNonBlank(title, "title");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireNonBlank(deferralRule, "deferralRule");
        requireNonBlank(deferralReason, "deferralReason");
        arguments = List.copyOf(arguments);
        provenance = List.copyOf(provenance);
        decisions = List.copyOf(decisions);
        Objects.requireNonNull(access, "access");
        requireNonBlank(digest, "digest");
    }
}
