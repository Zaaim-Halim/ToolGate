package io.toolgate.api;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.ArgumentSummary;
import io.toolgate.audit.AsyncAuditDispatcher;
import io.toolgate.audit.AuditChannel;
import io.toolgate.audit.FanOutSink;
import io.toolgate.audit.SystemLoggerSink;
import io.toolgate.engine.DefaultToolGate;
import io.toolgate.queue.InMemoryApprovalQueue;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.rules.ResultChecker;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.DecisionObserver;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision point between the model and every tool: the model proposes, ToolGate decides.
 *
 * <p>Adapters call {@link #check} for each tool call before the tool runs and apply the returned
 * {@link Decision}. Implementations are thread-safe; parallel calls in one session are checked
 * against consistent session state, so they cannot overshoot a budget together.
 *
 * <p>Every decision is also sent to the audit sinks, off the request path, and to the decision
 * observers, on it. {@link #close} delivers the events still queued; call it when the application
 * shuts down.
 */
public interface ToolGate extends AutoCloseable {

    /**
     * Decides whether a call may run, and records the decision in the call's session.
     *
     * <p>Fails closed instead of throwing: a rule that cannot be evaluated denies the call, and a
     * failing session store ends the run, because without session state no budget can be checked.
     *
     * @param call the call the model asked for
     * @return the decision to apply
     * @throws NullPointerException if {@code call} is null
     */
    Decision check(ToolCall call);

    /**
     * Decides a call as {@link #check(ToolCall)} does, with the definition the model was offered for this
     * call's tool. Pins and declared arguments are checked against it instead of the tool catalog, so two
     * requests offering different definitions under one tool name can never be checked against each
     * other's. Other tools, such as a downgrade's safe tool, are still looked up in the catalog.
     *
     * @param call    the call the model asked for
     * @param offered the definition offered for the call's tool, such as the framework's callback has
     * @return the decision to apply
     * @throws IllegalArgumentException if the definition is not of the call's tool
     * @throws NullPointerException     if either is null
     */
    default Decision check(ToolCall call, ToolDefinition offered) {
        if (!offered.tool().equals(call.tool())) {
            throw new IllegalArgumentException("definition of " + offered.tool() + " offered for a call to " + call.tool());
        }
        return check(call, tool -> tool.equals(offered.tool()) ? Optional.of(offered) : Optional.empty());
    }

    /**
     * Decides a call with the definitions offered to the model in the same request: the call's own tool,
     * and any other the decision involves, such as a downgrade's safe tool. Tools the request did not
     * offer are looked up in the gate's tool catalog.
     *
     * @param call    the call the model asked for
     * @param offered the definitions the request offered, by tool
     * @return the decision to apply
     * @throws NullPointerException if either is null
     */
    Decision check(ToolCall call, ToolCatalog offered);

    /**
     * Records a call the adapter refused before it could be checked, such as one whose arguments it
     * could not read unambiguously. The refusal is audited and counts toward the denial budget like any
     * deny, so a model cannot repeat it without limit; run-ending limits are checked first. In monitor
     * mode it is recorded as computed and Allow is returned, as for any rule.
     *
     * @param call   the call as far as it could be read; arguments it could not read are left out
     * @param ruleId one of {@link io.toolgate.api.policy.BuiltInRules#ADAPTER_REFUSALS}
     * @param reason why, for the audit trail
     * @return Deny, Terminate once a run-ending limit is reached, or Allow in monitor and off modes
     * @throws IllegalArgumentException if the rule is not an adapter refusal, or the reason is blank
     */
    Decision reject(ToolCall call, String ruleId, String reason);

    /**
     * Checks what a tool returned against the agent's result rules, and records it. Call it for
     * every tool that ran, with the call that actually ran (for a downgrade, the safe tool), before
     * handing the output to the model. Calls that were denied, deferred or ended the run did not run.
     *
     * <p>On {@link ResultDecision.Release}, the adapter hands the output to the model, and the
     * tool's source labels, {@link Label#PRIVATE_DATA} and {@link Label#UNTRUSTED_CONTENT}, taint the
     * session. On {@link ResultDecision.Withhold}, the adapter gives the model
     * {@link ResultDecision.Withhold#MODEL_MESSAGE} instead; the session gains no labels, since the
     * model saw nothing, and counts a denial.
     *
     * <p>Unlike {@link #check}, this throws if the session store fails: a lost taint record would
     * make later decisions more lenient than they should be, so the adapter must end the run.
     *
     * @param callThatRan the call whose tool ran
     * @param result      what the tool returned, or the error it failed with
     * @return whether the model may see it
     */
    ResultDecision recordResult(ToolCall callThatRan, ToolResult result);

    /**
     * Removes a source label from a session, after application code checked the data, for example
     * by parsing a fetched page into a strict schema. Every declassification is recorded with its
     * justification. Never let a tool result or the model trigger it.
     *
     * @param sessionId     the session
     * @param label         {@link Label#PRIVATE_DATA} or {@link Label#UNTRUSTED_CONTENT}
     * @param justification why the data is now safe
     * @throws IllegalArgumentException if the label is not a source label, or the justification is blank
     */
    void declassify(String sessionId, Label label, String justification);

    /**
     * Approves a deferred call, after re-checking it against the current policy, the session's
     * current state and the principal as it is now. The re-check skips run-ending budgets, since the
     * call was counted when it was deferred, and treats a deferral by the same rule as covered by
     * this approval. Any denial, a new reason to defer, or a downgrade refuses the release.
     *
     * <p>A call is released at most once: a second approval returns {@link Approval.AlreadyDecided}.
     * Who may approve, as {@link ApprovalItem#mayDecide()} reports it: a principal with one of the
     * agent's reviewer roles in the call's tenant, or with one of the policy's super admin roles in
     * any tenant, and in either case never their own call; in an interactive agent, the signed-in
     * user confirms their own calls. Anyone else gets {@link Approval.NotPermitted}, and the call
     * stays pending.
     *
     * @param approvalId       from {@link Decision.Defer#approvalId()}
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so that revoked roles count
     * @return the outcome; on {@link Approval.Released}, run the tool
     * @throws IllegalArgumentException if no call has that ID, or the principal is not the call's
     */
    Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal);

    /**
     * Rejects a deferred call. Rejecting needs the same permission as approving, so nobody can
     * reject another's legitimate calls. A call that is no longer pending is left as it is.
     *
     * @param approvalId from {@link Decision.Defer#approvalId()}
     * @param reviewer   who rejects
     * @param reason     why, for the audit trail
     * @return {@code true} if the call was pending and is now rejected; {@code false} if it was
     *         already decided, or the reviewer may not decide it
     * @throws IllegalArgumentException if no call has that ID
     */
    boolean reject(String approvalId, Principal reviewer, String reason);

    /**
     * Every call waiting for review that has not expired, of every agent and tenant, oldest first.
     * For operators and the application's own jobs only: a screen a person uses must call
     * {@link #pending(Principal)}, or it shows every tenant's arguments to whoever opens it.
     *
     * @return the pending calls, with their real arguments
     */
    List<PendingCall> pending();

    /**
     * The calls waiting for review that a viewer may see, oldest first: as a super admin or auditor,
     * every call; as a reviewer, the calls of agents naming one of their roles, in their tenant; and
     * their own calls, as the requester. Each says whether the viewer may decide it.
     *
     * @param viewer who is looking
     * @return the calls, with their real arguments; empty if the viewer may see none
     */
    List<ApprovalItem> pending(Principal viewer);

    /**
     * Expires every pending call past its expiry. Approving checks expiry too, so this only keeps
     * the queue and the audit trail current; run it on a schedule, such as every minute.
     *
     * @return how many calls expired
     */
    int expirePending();

    /**
     * Gaps in the audit trail: events not delivered to every sink, because their queue was full, a sink
     * threw (an event counts once however many sinks missed it), or the event came after
     * {@link #close}; plus decision observer calls that threw. Worth a metric and an alert:
     * enforcement is unaffected, but the audit trail has gaps.
     *
     * @return how many were dropped since the gate was built
     */
    long droppedAuditEvents();

    /**
     * Delivers queued audit events, waiting a few seconds at most, and stops auditing. Checks still
     * work afterwards, so a call racing shutdown is still decided; its audit event is dropped.
     */
    @Override
    void close();

    /**
     * Starts building a gate.
     *
     * @param policy the policy the gate enforces
     * @return a builder with defaults for everything else
     */
    static Builder builder(ToolGatePolicy policy) {
        return new Builder(policy);
    }

    /** Builds a {@link ToolGate}; every setting except the policy has a default. */
    final class Builder {

        private final ToolGatePolicy policy;
        private SessionStore sessionStore;
        private ApprovalQueue approvalQueue;
        private HostResolver hostResolver;
        private OwnershipResolver ownershipResolver;
        private ToolCatalog toolCatalog;
        private final List<AuditSink> auditSinks = new ArrayList<>();
        private boolean defaultLog = true;
        private final List<DecisionObserver> decisionObservers = new ArrayList<>();
        private byte[] argumentHashKey;
        private Clock clock = Clock.systemUTC();

        private Builder(ToolGatePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        /**
         * Where session state is kept. Defaults to an in-memory store, which is correct only when
         * every call of a session reaches the same instance.
         *
         * @param store the store to use
         * @return this builder
         */
        public Builder sessionStore(SessionStore store) {
            this.sessionStore = Objects.requireNonNull(store, "sessionStore");
            return this;
        }

        /**
         * Where deferred calls wait for review. Defaults to an in-memory queue, which loses pending
         * calls on restart and is correct only for a single instance.
         *
         * @param queue the queue to use
         * @return this builder
         */
        public Builder approvalQueue(ApprovalQueue queue) {
            this.approvalQueue = Objects.requireNonNull(queue, "approvalQueue");
            return this;
        }

        /**
         * Also resolves URL host names during checks, and denies names that resolve to private,
         * loopback, link-local or other special-purpose addresses. Off by default: see
         * {@link HostResolver} for the costs.
         *
         * @param resolver the resolver, such as {@link HostResolver#system()}
         * @return this builder
         */
        public Builder hostResolver(HostResolver resolver) {
            this.hostResolver = Objects.requireNonNull(resolver, "hostResolver");
            return this;
        }

        /**
         * Answers who owns a record, for {@code sameTenant} and {@code ownedByPrincipal} rules.
         * Required when the policy uses them.
         *
         * @param resolver the application's resolver; fast, since it runs during every such check
         * @return this builder
         */
        public Builder ownershipResolver(OwnershipResolver resolver) {
            this.ownershipResolver = Objects.requireNonNull(resolver, "ownershipResolver");
            return this;
        }

        /**
         * Gives each tool's current definition, for pinned tools and for tools with argument rules,
         * whose calls may only pass the arguments the definition declares. Required when the policy
         * pins any tool or has any argument rule; keep it current whenever a framework or MCP server
         * lists its tools again.
         *
         * @param catalog the catalog, such as {@link ToolCatalog#inMemory()}; read on every call to a pinned tool
         * @return this builder
         */
        public Builder toolCatalog(ToolCatalog catalog) {
            this.toolCatalog = Objects.requireNonNull(catalog, "toolCatalog");
            return this;
        }

        /**
         * Also sends audit events to a sink, such as a SIEM or a metrics registry. Every sink gets
         * every event, in the order added, after the default log unless {@link #noDefaultLog} was
         * called; a failing sink never keeps an event from the others.
         *
         * @param sink the sink, called from one background thread
         * @return this builder
         */
        public Builder auditSink(AuditSink sink) {
            auditSinks.add(Objects.requireNonNull(sink, "auditSink"));
            return this;
        }

        /**
         * Turns off the default audit log: one JSON line per event on the JDK logger
         * {@code toolgate.audit}, which Spring Boot routes to SLF4J. Without it and without any
         * {@link #auditSink}, nothing is audited.
         *
         * @return this builder
         */
        public Builder noDefaultLog() {
            this.defaultLog = false;
            return this;
        }

        /**
         * Also tells an observer about each check as it ends, on the checking thread, such as to
         * annotate the tool call's trace span. Observers are called in the order added.
         *
         * @param observer the observer; fast and non-blocking, since it runs on every check
         * @return this builder
         */
        public Builder decisionObserver(DecisionObserver observer) {
            decisionObservers.add(Objects.requireNonNull(observer, "decisionObserver"));
            return this;
        }

        /**
         * Logs arguments as keyed hashes, so equal values can be correlated across calls and
         * instances without being readable. Without a key, only each argument's type and size are
         * logged.
         *
         * @param key at least 32 secret bytes, the same on every instance; copied
         * @return this builder
         * @throws IllegalArgumentException if the key is shorter than 32 bytes
         */
        public Builder argumentHashKey(byte[] key) {
            if (key.length < ArgumentSummary.MIN_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "argument hash key needs at least " + ArgumentSummary.MIN_KEY_BYTES + " bytes");
            }
            this.argumentHashKey = Arrays.copyOf(key, key.length);
            return this;
        }

        /**
         * The clock for audit event times. Defaults to the system clock in UTC.
         *
         * @param newClock the clock
         * @return this builder
         */
        public Builder clock(Clock newClock) {
            this.clock = Objects.requireNonNull(newClock, "clock");
            return this;
        }

        /**
         * Builds the gate and starts its audit thread.
         *
         * @return a thread-safe gate; close it at shutdown
         * @throws IllegalStateException if the policy uses ownership rules and no
         *                               {@link #ownershipResolver} was given, or pins tools or has
         *                               argument rules and no {@link #toolCatalog} was given
         */
        public ToolGate build() {
            if (ownershipResolver == null && BuiltInEvaluators.usesOwnership(policy)) {
                throw new IllegalStateException(
                        "the policy uses sameTenant or ownedByPrincipal rules; set an ownershipResolver");
            }
            if (toolCatalog == null && BuiltInEvaluators.needsCatalog(policy)) {
                throw new IllegalStateException(
                        "the policy pins tool definitions or has argument rules; set a toolCatalog");
            }
            OwnershipResolver owners = ownershipResolver != null ? ownershipResolver : BuiltInEvaluators.NO_OWNERS;
            return new DefaultToolGate(
                    policy,
                    BuiltInEvaluators.forPolicy(policy,
                            hostResolver != null ? BuiltInEvaluators.resolving(hostResolver) : BuiltInEvaluators.NO_NAME_CHECK,
                            owners),
                    new ResultChecker(policy, owners),
                    sessionStore != null ? sessionStore : new InMemorySessionStore(),
                    approvalQueue != null ? approvalQueue : new InMemoryApprovalQueue(),
                    auditChannel(),
                    decisionObservers,
                    argumentHashKey != null ? ArgumentSummary.keyed(argumentHashKey) : ArgumentSummary.typesOnly(),
                    clock,
                    toolCatalog != null ? toolCatalog : BuiltInEvaluators.NO_TOOLS);
        }

        private AuditChannel auditChannel() {
            var sinks = new ArrayList<AuditSink>();
            if (defaultLog) {
                sinks.add(new SystemLoggerSink());
            }
            sinks.addAll(auditSinks);
            return switch (sinks.size()) {
                case 0 -> AuditChannel.NONE;
                case 1 -> new AsyncAuditDispatcher(sinks.getFirst(), AsyncAuditDispatcher.DEFAULT_CAPACITY);
                default -> new AsyncAuditDispatcher(new FanOutSink(sinks), AsyncAuditDispatcher.DEFAULT_CAPACITY);
            };
        }
    }
}
