package io.toolgate.springai;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalItem;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.spi.PendingCall;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Decides deferred calls and runs the approved ones. ToolGate re-checks an approved call against the
 * current policy, session and principal; this then runs it, once, with the model's own arguments, and
 * records the result, so the session's taint and totals stay right.
 *
 * <pre>{@code
 * Approval outcome = approvals.approve(pending.approvalId(), reviewer, currentPrincipalOf(pending));
 * }</pre>
 *
 * <p>The tool is the one the request offered, kept when the call was deferred, or else a tool the
 * application registered as a bean. Kept tools live on the instance that deferred the call: in a
 * cluster, register tools that need approval as beans, so any instance can run them.
 */
public final class ToolGateApprovals {

    private final Supplier<Gating> gating;

    ToolGateApprovals(Supplier<Gating> gating) {
        this.gating = gating;
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
     * may decide it.
     *
     * @param viewer who is looking, such as the signed-in reviewer
     * @return the calls, with their arguments; see {@link io.toolgate.api.ToolGate#pending(Principal)}
     */
    public List<ApprovalItem> pending(Principal viewer) {
        return gating.get().gate().pending(viewer);
    }

    /**
     * Approves a deferred call and, if ToolGate releases it, runs it. The tool is found before
     * approving: when this instance cannot run it, nothing is approved and the call stays pending.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @return ToolGate's outcome; on {@link Approval.Released} the tool has run and its result is recorded
     * @throws IllegalArgumentException if no call has that ID, or the principal is not the call's
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal) {
        Gating shared = gating.get();
        Optional<PendingCall> waiting = shared.gate().pending().stream()
                .filter(pending -> pending.approvalId().equals(approvalId)).findFirst();
        if (waiting.isEmpty()) {
            // Decided, expired or unknown: ToolGate says which, and nothing will run.
            shared.forget(approvalId);
            return shared.gate().approve(approvalId, reviewer, currentPrincipal);
        }
        ToolCall deferred = waiting.get().call();
        Optional<Gating.Kept> tool = shared.kept(approvalId).or(() -> shared.registered(deferred.tool())
                .map(callback -> new Gating.Kept(callback, ArgValueJson.write(deferred.args()))));
        if (tool.isEmpty()) {
            return new Approval.NotPermitted(deferred.tool() + " cannot run on this instance: it was offered with a request"
                    + " on another instance, or that instance restarted; register the tool as a bean so any instance can run it");
        }
        // An instance started after the deferral has not seen the tool offered yet; the re-check needs its
        // definition for pins and declared arguments, or it refuses every call with argument rules.
        shared.definition(deferred.tool(), tool.get().callback());
        Approval approval = shared.gate().approve(approvalId, reviewer, currentPrincipal);
        if (approval instanceof Approval.Released released) {
            shared.forget(approvalId);
            ToolCall call = released.call();
            var context = new HashMap<String, Object>(ToolGateContext.of(call.agentId(), call.principal(), call.sessionId()));
            context.put(ToolGateContext.IDEMPOTENCY_KEY, released.idempotencyKey());
            shared.run(tool.get().callback(), call, tool.get().input(), new ToolContext(context));
        }
        return approval;
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
}
