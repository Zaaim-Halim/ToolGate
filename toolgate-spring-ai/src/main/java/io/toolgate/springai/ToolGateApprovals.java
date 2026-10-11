package io.toolgate.springai;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalQuery;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Journey;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolResult;
import io.toolgate.spi.PendingCall;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Decides deferred calls and runs the approved ones. ToolGate re-checks an approved call against the
 * current policy, session and principal; this then claims it, runs it once with the model's own
 * arguments, records the result for the session, and reports the outcome to the queue, so it says
 * {@link ApprovalStatus#EXECUTED} or {@link ApprovalStatus#FAILED}, or {@link ApprovalStatus#UNKNOWN}
 * if this instance dies mid-run.
 *
 * <pre>{@code
 * Approval outcome = approvals.approve(item.call().approvalId(), reviewer, currentPrincipalOf(item), "order checked");
 * }</pre>
 *
 * <p>The tool is the one the request offered, kept when the call was deferred, or else a tool the
 * application registered as a bean. Kept tools live on the instance that deferred the call: in a
 * cluster, register tools that need approval as beans, so any instance can run them. Calls approved
 * elsewhere, such as from a separate console, are run by {@link #runApproved()}, which the
 * auto-configuration schedules.
 */
public final class ToolGateApprovals {

    private final Supplier<Gating> gating;
    private final String executor;
    private final Duration lease;

    ToolGateApprovals(Supplier<Gating> gating, String executor, Duration lease) {
        this.gating = gating;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    /**
     * Every call waiting for review, of every agent and tenant, oldest first: for operators and the
     * application's own jobs only. A screen a person uses calls {@link #pending(Principal)}.
     *
     * @return the pending calls, with their arguments
     */
    public List<PendingCall> pending() {
        return gating.get().gate().pending();
    }

    /**
     * The calls waiting for review that a viewer may see, oldest first, each saying whether the viewer
     * may decide it, masked by the policy's review view.
     *
     * @param viewer who is looking, such as the signed-in reviewer
     * @return the calls as review items; see {@link io.toolgate.api.ToolGate#pending(Principal)}
     */
    public List<ReviewItem> pending(Principal viewer) {
        return gating.get().gate().pending(viewer);
    }

    /**
     * Approves a deferred call with no comment; see {@link #approve(String, Principal, Principal, String)}.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @return ToolGate's outcome
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal) {
        return approve(approvalId, reviewer, currentPrincipal, null);
    }

    /**
     * Approves a deferred call and, if ToolGate releases it, runs it here. The tool is found before
     * approving: when this instance cannot run it, nothing is approved and the call stays pending.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @param comment          the reviewer's justification; required of a super admin
     * @return ToolGate's outcome; on {@link Approval.Released} the call ran, here or, if another
     *         instance's executor claimed it first, there; its status says how it ended
     * @throws IllegalArgumentException if no call has that ID, or the principal is not the call's
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal, String comment) {
        return approve(approvalId, reviewer, currentPrincipal, comment, null);
    }

    /**
     * Approves a deferred call, as {@link #approve(String, Principal, Principal, String)} does, only if
     * the review screen showed exactly what would be approved; a review screen always passes the digest.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @param comment          the reviewer's justification; required of a super admin
     * @param digest           {@link ReviewItem#digest()} of what the reviewer saw; null to skip the check
     * @return ToolGate's outcome
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal, String comment,
                            String digest) {
        Gating shared = gating.get();
        Optional<PendingCall> waiting = shared.gate().pending().stream()
                .filter(pending -> pending.approvalId().equals(approvalId)).findFirst();
        if (waiting.isEmpty()) {
            // Decided, expired or unknown: ToolGate says which, and nothing will run.
            shared.forget(approvalId);
            return shared.gate().approve(approvalId, reviewer, currentPrincipal, comment, digest);
        }
        Optional<Gating.Kept> tool = runnable(shared, waiting.get());
        if (tool.isEmpty()) {
            return new Approval.NotPermitted(waiting.get().call().tool() + " cannot run on this instance: it was offered"
                    + " with a request on another instance, or that instance restarted; register the tool as a bean so"
                    + " any instance can run it");
        }
        Approval approval = shared.gate().approve(approvalId, reviewer, currentPrincipal, comment, digest);
        if (approval instanceof Approval.Released) {
            execute(shared, approvalId, tool.get());
        }
        return approval;
    }

    /**
     * Calls a viewer may see in the query's statuses, newest first, masked; see
     * {@link io.toolgate.api.ToolGate#history}.
     *
     * @param viewer who is looking
     * @param query  which calls
     * @return the calls as review items
     */
    public List<ReviewItem> history(Principal viewer, ApprovalQuery query) {
        return gating.get().gate().history(viewer, query);
    }

    /**
     * The story of one deferred call as a graph; see {@link io.toolgate.api.ToolGate#journey}.
     *
     * @param approvalId the call
     * @param viewer     who is looking
     * @return the journey, or empty if the viewer may not see the call
     */
    public Optional<Journey> journey(String approvalId, Principal viewer) {
        return gating.get().gate().journey(approvalId, viewer);
    }

    /**
     * One deferred call as a viewer may see it on a review screen; see {@link io.toolgate.api.ToolGate#reviewItem}.
     *
     * @param approvalId the call
     * @param viewer     who is looking
     * @return the item, or empty if the viewer may not see it
     */
    public Optional<ReviewItem> reviewItem(String approvalId, Principal viewer) {
        return gating.get().gate().reviewItem(approvalId, viewer);
    }

    /**
     * Rejects a deferred call; it will never run.
     *
     * @param approvalId the pending call
     * @param reviewer   who rejects
     * @param reason     why, for the audit trail
     * @return {@code true} if the call was pending and is now rejected
     */
    public boolean reject(String approvalId, Principal reviewer, String reason) {
        boolean rejected = gating.get().gate().reject(approvalId, reviewer, reason);
        if (rejected) {
            gating.get().forget(approvalId);
        }
        return rejected;
    }

    /**
     * Withdraws a deferred call nobody decided yet; see {@link io.toolgate.api.ToolGate#cancel}.
     *
     * @param approvalId the pending call
     * @param by         its requester, or a super admin
     * @param reason     why, for the audit trail
     * @return {@code true} if the call was pending and is now cancelled
     */
    public boolean cancel(String approvalId, Principal by, String reason) {
        boolean cancelled = gating.get().gate().cancel(approvalId, by, reason);
        if (cancelled) {
            gating.get().forget(approvalId);
        }
        return cancelled;
    }

    /**
     * Records how a run with an unknown outcome really ended; see {@link io.toolgate.api.ToolGate#resolve}.
     *
     * @param approvalId the call
     * @param by         a reviewer of its tenant, or a super admin
     * @param ran        whether the tool did run
     * @param reason     what they found
     * @return {@code true} if resolved
     */
    public boolean resolve(String approvalId, Principal by, boolean ran, String reason) {
        return gating.get().gate().resolve(approvalId, by, ran, reason);
    }

    /**
     * Runs the released calls this instance can run that nobody claimed yet, such as calls approved from
     * another process. Claiming is compare-and-set, so across instances each call runs once. A tool that
     * fails is recorded as {@link ApprovalStatus#FAILED} and the others still run.
     *
     * @return how many calls this instance ran, whether they succeeded or failed
     */
    public int runApproved() {
        Gating shared = gating.get();
        int ran = 0;
        for (PendingCall released : shared.gate().withStatus(ApprovalStatus.RELEASED)) {
            Optional<Gating.Kept> tool = runnable(shared, released);
            if (tool.isEmpty()) {
                continue;
            }
            try {
                ran += execute(shared, released.approvalId(), tool.get()) ? 1 : 0;
            } catch (RuntimeException failed) {
                // Already recorded as FAILED, with the error, by execute.
                ran++;
            }
        }
        return ran;
    }

    /** The tool a call needs, if this instance has it: kept from its request, or registered as a bean. */
    private static Optional<Gating.Kept> runnable(Gating shared, PendingCall call) {
        ToolCall deferred = call.call();
        Optional<Gating.Kept> tool = shared.kept(call.approvalId()).or(() -> shared.registered(deferred.tool())
                .map(callback -> new Gating.Kept(callback, ArgValueJson.write(deferred.args()))));
        // An instance started after the deferral has not seen the tool offered yet; the re-check needs its
        // definition for pins and declared arguments, or it refuses every call with argument rules.
        tool.ifPresent(found -> shared.definition(deferred.tool(), found.callback()));
        return tool;
    }

    /**
     * Claims a released call and runs it, reporting the outcome. Nothing runs if another executor
     * claimed it first; if this instance dies mid-run, the lease runs out and the call becomes
     * {@link ApprovalStatus#UNKNOWN}.
     */
    private boolean execute(Gating shared, String approvalId, Gating.Kept tool) {
        Optional<Approval.Released> claimed = shared.gate().claim(approvalId, executor, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        shared.forget(approvalId);
        ToolCall call = claimed.get().call();
        var context = new HashMap<String, Object>(ToolGateContext.of(call.agentId(), call.principal(), call.sessionId()));
        context.put(ToolGateContext.IDEMPOTENCY_KEY, claimed.get().idempotencyKey());
        try {
            String output = WithoutSignedInUser.call(() -> shared.run(tool.callback(), call, tool.input(),
                    new ToolContext(context)));
            shared.gate().finished(approvalId, executor, new ToolResult.Succeeded(ArgValue.text(output)));
        } catch (RuntimeException failure) {
            shared.gate().finished(approvalId, executor, new ToolResult.Failed(failure.toString()));
            throw failure;
        }
        return true;
    }
}
