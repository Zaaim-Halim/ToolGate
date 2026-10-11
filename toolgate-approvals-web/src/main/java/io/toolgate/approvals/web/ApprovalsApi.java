package io.toolgate.approvals.web;

import io.toolgate.api.Approval;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewItem;
import io.toolgate.springai.ToolGateApprovals;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * What the REST API does, whatever the web stack: every rule lives in ToolGate's core, so this only
 * resolves who is asking and translates. The controllers add HTTP; this never touches it.
 */
final class ApprovalsApi {

    private final ToolGateApprovals approvals;
    private final ToolGateReviewerResolver reviewers;
    private final ToolGateRequesterDirectory requesters;
    private final String application;

    ApprovalsApi(ToolGateApprovals approvals, ToolGateReviewerResolver reviewers, ToolGateRequesterDirectory requesters,
                 String application) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.reviewers = Objects.requireNonNull(reviewers, "reviewers");
        this.requesters = Objects.requireNonNull(requesters, "requesters");
        this.application = Objects.requireNonNull(application, "application");
    }

    /** The inbox: the calls the viewer may see, masked. */
    List<ApiModel.Item> inbox(java.security.Principal user) {
        return approvals.pending(viewer(user)).stream().map(ApiModel.Item::of).toList();
    }

    /** One call, or 404 if it does not exist or the viewer may not see it. */
    ApiModel.Item item(java.security.Principal user, String approvalId) {
        return ApiModel.Item.of(visible(viewer(user), approvalId));
    }

    /** Who is signed in. */
    ApiModel.Me me(java.security.Principal user) {
        return new ApiModel.Me(ApiModel.Party.of(viewer(user)), application);
    }

    /** Applies a reviewer's decision. */
    ApiModel.DecisionResponse decide(java.security.Principal user, ApiModel.DecisionRequest request) {
        Principal viewer = viewer(user);
        if (request == null || request.approvalId() == null || request.approvalId().isBlank() || request.kind() == null) {
            throw ApiException.badRequest("approvalId and kind are required");
        }
        ReviewItem item = visible(viewer, request.approvalId());
        String comment = blankToNull(request.comment());
        return switch (request.kind().toLowerCase(Locale.ROOT)) {
            case "approve" -> {
                if (request.digest() == null || request.digest().isBlank()) {
                    throw ApiException.badRequest("approve needs the digest of the item the reviewer saw");
                }
                yield outcome(approvals.approve(item.approvalId(), viewer, requesters.current(item.requester()),
                        comment, request.digest()));
            }
            case "reject" -> done(approvals.reject(item.approvalId(), viewer, required(comment, "reject")));
            case "cancel" -> done(approvals.cancel(item.approvalId(), viewer, required(comment, "cancel")));
            case "resolve" -> {
                if (request.ran() == null) {
                    throw ApiException.badRequest("resolve needs ran: whether the tool did run");
                }
                yield done(approvals.resolve(item.approvalId(), viewer, request.ran(), required(comment, "resolve")));
            }
            default -> throw ApiException.badRequest("kind must be approve, reject, cancel or resolve");
        };
    }

    private ReviewItem visible(Principal viewer, String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            throw ApiException.badRequest("id is required");
        }
        try {
            return approvals.reviewItem(approvalId, viewer).orElseThrow(ApiException::notFound);
        } catch (IllegalArgumentException unknown) {
            throw ApiException.notFound();
        }
    }

    private Principal viewer(java.security.Principal user) {
        if (!(user instanceof Authentication authentication) || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw ApiException.unauthenticated();
        }
        return reviewers.reviewer(authentication);
    }

    private static ApiModel.DecisionResponse outcome(Approval approval) {
        return switch (approval) {
            case Approval.Released released -> new ApiModel.DecisionResponse("released", null, null);
            case Approval.AwaitingConfirmation awaiting ->
                    new ApiModel.DecisionResponse("awaiting-confirmation", awaiting.reason(), "PENDING");
            case Approval.Refused refused -> new ApiModel.DecisionResponse("refused", refused.reason(), "REFUSED");
            case Approval.NotPermitted notPermitted -> new ApiModel.DecisionResponse("not-permitted", notPermitted.reason(), null);
            case Approval.AlreadyDecided decided ->
                    new ApiModel.DecisionResponse("already-decided", null, decided.status().name());
            case Approval.Expired expired -> new ApiModel.DecisionResponse("expired", null, "EXPIRED");
        };
    }

    private static ApiModel.DecisionResponse done(boolean done) {
        return new ApiModel.DecisionResponse(done ? "done" : "not-done", null, null);
    }

    private static String required(String comment, String kind) {
        if (comment == null) {
            throw ApiException.badRequest(kind + " needs a comment saying why");
        }
        return comment;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
