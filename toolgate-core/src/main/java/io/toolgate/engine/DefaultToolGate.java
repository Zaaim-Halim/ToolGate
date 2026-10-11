package io.toolgate.engine;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.Budget;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.ArgumentSummary;
import io.toolgate.audit.AuditChannel;
import io.toolgate.queue.InMemoryApprovalQueue;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.rules.ResultChecker;
import io.toolgate.rules.Spending;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.DecisionObserver;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallReleased;
import io.toolgate.spi.SessionEvent.Declassified;
import io.toolgate.spi.SessionEvent.ResultRecorded;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks calls with the engine and records each applied decision in the session, optimistically:
 * if another call in the same session was recorded in between, the check runs again on the new
 * state, so parallel calls can never overshoot a budget together. Every decision is then audited.
 */
public final class DefaultToolGate implements ToolGate {

    /**
     * An append only fails because another call in the session succeeded, so attempts are bounded
     * by the number of parallel calls. Far past any real burst, the store is broken, not busy.
     */
    private static final int MAX_APPEND_ATTEMPTS = 1024;

    /** The labels a session can hold; EXTERNAL_SINK describes tools, not data. */
    private static final Set<Label> SOURCE_LABELS = EnumSet.of(Label.PRIVATE_DATA, Label.UNTRUSTED_CONTENT);

    private final ToolGatePolicy policy;
    private final DecisionEngine engine;
    /** The engine without run-ending limits, for re-checking a deferred call that was already counted. */
    private final DecisionEngine releaseEngine;
    /** Run-ending limits alone, which a call the adapter refuses must still pass first. */
    private final List<Evaluator> limits;
    private final SessionStore sessions;
    private final ApprovalQueue approvals;
    private final ResultChecker results;
    private final AuditChannel audit;
    private final List<DecisionObserver> observers;
    /** Observer calls that threw; counted with the dropped audit events, since both leave gaps. */
    private final AtomicLong failedObservations = new AtomicLong();
    private final ArgumentSummary argumentSummary;
    private final Clock clock;
    private final ToolCatalog catalog;

    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, ResultChecker results,
                           SessionStore sessions, ApprovalQueue approvals, AuditChannel audit,
                           List<DecisionObserver> observers, ArgumentSummary argumentSummary, Clock clock,
                           ToolCatalog catalog) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.engine = new DecisionEngine(policy, evaluators);
        this.releaseEngine = new DecisionEngine(policy,
                evaluators.stream().filter(evaluator -> evaluator.stage() != Stage.LIMITS).toList());
        this.limits = evaluators.stream().filter(evaluator -> evaluator.stage() == Stage.LIMITS).toList();
        this.results = Objects.requireNonNull(results, "results");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.observers = List.copyOf(observers);
        this.argumentSummary = Objects.requireNonNull(argumentSummary, "argumentSummary");
        this.clock = Objects.requireNonNull(clock, "clock");
        Instant loadedAt = clock.instant();
        audit.submit(() -> new AuditEvent.PolicyLoaded(loadedAt, policy.hash(), policy.mode(), policy.agents().size()));
    }

    /** A gate without audit, for tests of decisions alone. */
    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, SessionStore sessions) {
        this(policy, evaluators, new ResultChecker(policy, BuiltInEvaluators.NO_OWNERS), sessions,
                new InMemoryApprovalQueue(), AuditChannel.NONE, List.of(), ArgumentSummary.typesOnly(), Clock.systemUTC(),
                BuiltInEvaluators.NO_TOOLS);
    }

    @Override
    public Decision check(ToolCall call) {
        Objects.requireNonNull(call, "call");
        return checkWith(call, catalog);
    }

    @Override
    public Decision check(ToolCall call, ToolCatalog offered) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(offered, "offered");
        return checkWith(call, tool -> offered.definition(tool).or(() -> catalog.definition(tool)));
    }

    @Override
    public Decision reject(ToolCall call, String ruleId, String reason) {
        Objects.requireNonNull(call, "call");
        if (!BuiltInRules.ADAPTER_REFUSALS.contains(ruleId)) {
            throw new IllegalArgumentException("not an adapter refusal: '" + ruleId + "'; use one of "
                    + BuiltInRules.ADAPTER_REFUSALS);
        }
        requireNonBlank(reason, "reason");
        var refusal = new Evaluator() {
            @Override
            public String id() {
                return ruleId;
            }

            @Override
            public Stage stage() {
                return Stage.STRUCTURE;
            }

            @Override
            public Verdict evaluate(EvaluationContext context) {
                return new Verdict.Deny(reason, Origin.ACCESS);
            }
        };
        var withLimits = new ArrayList<Evaluator>(limits);
        withLimits.add(refusal);
        return checkWith(call, catalog, new DecisionEngine(policy, withLimits));
    }

    private Decision checkWith(ToolCall call, ToolCatalog definitions) {
        return checkWith(call, definitions, engine);
    }

    private Decision checkWith(ToolCall call, ToolCatalog definitions, DecisionEngine deciding) {
        if (policy.mode() == Mode.OFF) {
            return new Decision.Allow(BuiltInRules.MODE_OFF);
        }
        long startedNanos = System.nanoTime();
        try {
            return decideAndRecord(call, definitions, deciding, startedNanos);
        } catch (RuntimeException storeFailure) {
            // Evaluators cannot throw past the engine, so this is the store. Without session state
            // every later call would be decided blind, so the run ends rather than this call alone.
            var ended = new Decision.Terminate(BuiltInRules.SESSION_UNAVAILABLE, "session store failed: " + storeFailure);
            report(call, ended, ended, 0, SessionState.empty(call.sessionId()), startedNanos);
            return ended;
        }
    }

    @Override
    public ResultDecision recordResult(ToolCall callThatRan, ToolResult result) {
        Objects.requireNonNull(callThatRan, "callThatRan");
        Objects.requireNonNull(result, "result");
        if (policy.mode() == Mode.OFF) {
            return ResultDecision.RELEASE;
        }
        ResultDecision computed = results.check(callThatRan, result);
        // Result rules follow the policy-wide mode: in monitor mode the output is released and the
        // audit event records what enforcement would have done.
        ResultDecision applied = policy.mode() == Mode.ENFORCE ? computed : ResultDecision.RELEASE;
        boolean withheld = applied instanceof ResultDecision.Withhold;
        var labels = EnumSet.noneOf(Label.class);
        if (!withheld) {
            labels.addAll(policy.tool(callThatRan.tool()).labels());
            labels.retainAll(SOURCE_LABELS);
        }
        // Taint only grows and denials only count up, so the event stays valid whatever was appended in between.
        sessions.append(new ResultRecorded(callThatRan.sessionId(), callThatRan.callId(), callThatRan.tool(),
                result instanceof ToolResult.Succeeded, withheld, labels, callThatRan.at()));
        if (computed instanceof ResultDecision.Withhold(String ruleId, String reason)) {
            Instant checkedAt = clock.instant();
            audit.submit(() -> new AuditEvent.ResultWithheld(checkedAt, callThatRan.callId(), callThatRan.sessionId(),
                    callThatRan.agentId(), callThatRan.principal(), callThatRan.tool(), ruleId, reason, withheld,
                    policy.hash()));
        }
        return applied;
    }

    @Override
    public void declassify(String sessionId, Label label, String justification) {
        Objects.requireNonNull(label, "label");
        if (!SOURCE_LABELS.contains(label)) {
            throw new IllegalArgumentException(label + " marks tools, not sessions, and cannot be declassified");
        }
        Instant at = clock.instant();
        sessions.append(new Declassified(sessionId, label, justification, at));
        audit.submit(() -> new AuditEvent.Declassified(at, sessionId, label, justification));
    }

    @Override
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(currentPrincipal, "currentPrincipal");
        PendingCall pending = pendingCall(approvalId);
        ToolCall call = pending.call();
        if (!currentPrincipal.id().equals(call.principal().id())) {
            throw new IllegalArgumentException("currentPrincipal " + currentPrincipal.id()
                    + " is not the call's principal " + call.principal().id());
        }
        if (pending.status() != ApprovalStatus.PENDING) {
            return new Approval.AlreadyDecided(pending.status());
        }
        if (!clock.instant().isBefore(pending.expiresAt())) {
            return expire(pending) ? new Approval.Expired() : alreadyDecided(approvalId);
        }
        Optional<String> notPermitted = permissionProblem(call, reviewer);
        if (notPermitted.isPresent()) {
            return new Approval.NotPermitted(notPermitted.get());
        }
        ToolCall released = new ToolCall(call.callId(), currentPrincipal, call.agentId(), call.sessionId(), call.tool(),
                call.args(), call.at());
        Optional<String> refusal = recheck(released, pending.deferral());
        ApprovalStatus outcome = refusal.isPresent() ? ApprovalStatus.REFUSED : ApprovalStatus.RELEASED;
        if (!approvals.transition(approvalId, ApprovalStatus.PENDING, outcome)) {
            return alreadyDecided(approvalId);
        }
        if (outcome == ApprovalStatus.RELEASED) {
            // After the queue says released, so a lost race records nothing. If this fails, approve throws:
            // the application never gets Released and never runs the call, which fails closed.
            sessions.append(new CallReleased(call.sessionId(), approvalId, call.callId(),
                    spent(released, new Decision.Allow(pending.deferral().ruleId())), clock.instant()));
        }
        auditApproval(pending, outcome, reviewer.id(), refusal.orElse("approved"));
        return refusal.<Approval>map(Approval.Refused::new)
                .orElseGet(() -> new Approval.Released(released, approvalId));
    }

    @Override
    public boolean reject(String approvalId, Principal reviewer, String reason) {
        Objects.requireNonNull(reviewer, "reviewer");
        PendingCall pending = pendingCall(approvalId);
        if (permissionProblem(pending.call(), reviewer).isPresent()
                || !approvals.transition(approvalId, ApprovalStatus.PENDING, ApprovalStatus.REJECTED)) {
            return false;
        }
        auditApproval(pending, ApprovalStatus.REJECTED, reviewer.id(), reason);
        return true;
    }

    @Override
    public List<PendingCall> pending() {
        Instant now = clock.instant();
        return approvals.pending().stream().filter(call -> now.isBefore(call.expiresAt())).toList();
    }

    @Override
    public int expirePending() {
        Instant now = clock.instant();
        int expired = 0;
        for (PendingCall call : approvals.pending()) {
            if (!now.isBefore(call.expiresAt()) && expire(call)) {
                expired++;
            }
        }
        return expired;
    }

    /** What a call adds to the session's totals: only a call that runs, which an allowed call does. */
    private Map<String, BigDecimal> spent(ToolCall call, Decision applied) {
        return applied instanceof Decision.Allow ? Spending.of(policy, call) : Map.of();
    }

    /**
     * Why this reviewer may not decide this call, if they may not. The signed-in user of an
     * interactive agent confirms their own calls; anyone else needs one of the agent's reviewer
     * roles, and nobody approves their own call in a headless agent.
     */
    private Optional<String> permissionProblem(ToolCall call, Principal reviewer) {
        Optional<AgentPolicy> agent = policy.agent(call.agentId());
        boolean ownCall = reviewer.id().equals(call.principal().id());
        if (agent.isPresent() && agent.get().interactive() && ownCall) {
            return Optional.empty();
        }
        Set<String> reviewers = agent.map(AgentPolicy::reviewers).orElse(Set.of());
        if (reviewers.isEmpty()) {
            return Optional.of("agent " + call.agentId() + " names no reviewers, so its calls cannot be approved");
        }
        if (Collections.disjoint(reviewers, reviewer.roles())) {
            return Optional.of("reviewer " + reviewer.id() + " has none of the reviewer roles " + new TreeSet<>(reviewers));
        }
        if (ownCall) {
            return Optional.of("principal " + reviewer.id() + " may not approve its own call");
        }
        return Optional.empty();
    }

    /**
     * Why the call may not run now, if anything changed since it was deferred. The deferral's own
     * rule is what the approval covers; any other objection was not seen by the reviewer.
     */
    private Optional<String> recheck(ToolCall call, Decision.Defer deferral) {
        Decision now = releaseEngine.evaluate(call, sessions.state(call.sessionId()), catalog).effective();
        return switch (now) {
            case Decision.Allow allowed -> Optional.empty();
            case Decision.Defer defer -> defer.ruleId().equals(deferral.ruleId())
                    ? Optional.empty()
                    : Optional.of("a new reason to defer, not seen by the reviewer: " + defer.ruleId() + ": " + defer.reason());
            case Decision.Downgrade downgrade -> Optional.of("the call would now be downgraded to " + downgrade.safeTool()
                    + " by " + downgrade.ruleId());
            case Decision.Deny denied -> Optional.of("now denied by " + denied.ruleId() + ": " + denied.reason());
            case Decision.Terminate ended -> Optional.of("now ended by " + ended.ruleId() + ": " + ended.reason());
        };
    }

    private PendingCall pendingCall(String approvalId) {
        return approvals.get(Objects.requireNonNull(approvalId, "approvalId"))
                .orElseThrow(() -> new IllegalArgumentException("no deferred call has approval ID " + approvalId));
    }

    private Approval alreadyDecided(String approvalId) {
        return new Approval.AlreadyDecided(pendingCall(approvalId).status());
    }

    private boolean expire(PendingCall pending) {
        if (!approvals.transition(pending.approvalId(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED)) {
            return false;
        }
        auditApproval(pending, ApprovalStatus.EXPIRED, "toolgate", "nobody decided before " + pending.expiresAt());
        return true;
    }

    private void auditApproval(PendingCall pending, ApprovalStatus status, String decidedBy, String reason) {
        Instant at = clock.instant();
        ToolCall call = pending.call();
        audit.submit(() -> new AuditEvent.ApprovalDecided(at, pending.approvalId(), call.sessionId(), call.agentId(),
                call.tool(), status, decidedBy, reason));
    }

    @Override
    public long droppedAuditEvents() {
        return audit.dropped() + failedObservations.get();
    }

    @Override
    public void close() {
        audit.close();
    }

    private Decision decideAndRecord(ToolCall call, ToolCatalog definitions, DecisionEngine deciding,
                                     long startedNanos) {
        for (int attempt = 0; attempt < MAX_APPEND_ATTEMPTS; attempt++) {
            SessionState state = sessions.state(call.sessionId());
            Evaluation evaluation = decide(call, state, definitions, deciding);
            Decision decision = evaluation.effective();
            var event = new CallDecided(call.sessionId(), call.callId(), call.agentId(), call.principal(), call.tool(),
                    call.args(), decision, evaluation.computed(), spent(call, decision), policy.hash(), call.at());
            if (decision instanceof Decision.Terminate) {
                sessions.append(event);
                report(call, decision, evaluation.computed(), state.apply(event).toolCalls(), state, startedNanos);
                return decision;
            }
            if (sessions.append(event, state.version())) {
                Decision queued = decision instanceof Decision.Defer defer ? enqueue(call, defer) : decision;
                report(call, queued, evaluation.computed(), state.apply(event).toolCalls(), state, startedNanos);
                return queued;
            }
        }
        var contention = new Decision.Deny(BuiltInRules.SESSION_CONTENTION,
                "session " + call.sessionId() + " changed on each of " + MAX_APPEND_ATTEMPTS + " attempts");
        report(call, contention, contention, 0, SessionState.empty(call.sessionId()), startedNanos);
        return contention;
    }

    /**
     * Puts a deferred call in the approval queue, after the session recorded the deferral: queueing
     * first could leave a pending call whose check, retried after a session conflict, allowed the call,
     * and a later approval would run it twice.
     */
    private Decision enqueue(ToolCall call, Decision.Defer defer) {
        Instant now = clock.instant();
        Duration expiry = policy.agent(call.agentId()).orElseThrow().approvalExpiry();
        try {
            approvals.add(new PendingCall(defer.approvalId(), call, defer, now, now.plus(expiry), ApprovalStatus.PENDING));
            return defer;
        } catch (RuntimeException queueFailure) {
            // Fail closed: telling the model the action is pending when nobody will see it would be a lie.
            return new Decision.Deny(BuiltInRules.APPROVAL_UNAVAILABLE, "approval queue failed: " + queueFailure);
        }
    }

    /** The engine's evaluation, with a downgrade replaced by its safe call's decision when that is stricter. */
    private Evaluation decide(ToolCall call, SessionState state, ToolCatalog definitions, DecisionEngine deciding) {
        Evaluation evaluation = deciding.evaluate(call, state, definitions);
        if (!(evaluation.effective() instanceof Decision.Downgrade downgrade)) {
            return evaluation;
        }
        return new Evaluation(checkSafeCall(call, state, downgrade, definitions), evaluation.computed());
    }

    private Decision checkSafeCall(ToolCall call, SessionState state, Decision.Downgrade downgrade,
                                   ToolCatalog definitions) {
        // The safe tool is checked like any other call, once, against the same state, so a downgrade
        // can never reach a tool the agent may not call. Its own decision wins unless it allows.
        Decision safe = engine.evaluate(call.withTool(downgrade.safeTool(), downgrade.safeArgs()), state, definitions).effective();
        return switch (safe) {
            case Decision.Allow allowed -> downgrade;
            case Decision.Downgrade chained -> new Decision.Deny(BuiltInRules.DOWNGRADE_CHAIN,
                    "safe tool " + downgrade.safeTool() + " would itself be downgraded to " + chained.safeTool());
            case Decision.Deny denied -> denied;
            case Decision.Defer deferred -> deferred;
            case Decision.Terminate terminated -> terminated;
        };
    }

    /** Ends a check: tells the observers, on this thread, and queues the audit event. */
    private void report(ToolCall call, Decision applied, Decision computed, int toolCalls, SessionState before,
                        long startedNanos) {
        Duration took = Duration.ofNanos(System.nanoTime() - startedNanos);
        if (!observers.isEmpty()) {
            var check = new DecisionObserver.Check(call, applied, computed, policy.mode(), took);
            for (DecisionObserver observer : observers) {
                try {
                    observer.decided(check);
                } catch (RuntimeException failed) {
                    // An observer must never change a decision, so its failure only leaves a gap.
                    failedObservations.incrementAndGet();
                }
            }
        }
        audit(call, applied, computed, toolCalls, before);
    }

    /** Queues the audit event; the time is taken now, and the arguments are summarized off this thread. */
    private void audit(ToolCall call, Decision applied, Decision computed, int toolCalls, SessionState before) {
        Instant decidedAt = clock.instant();
        int limit = policy.agent(call.agentId()).map(agent -> agent.budget().toolCalls())
                .orElse(Budget.UNLIMITED.toolCalls());
        audit.submit(() -> new AuditEvent.Decided(decidedAt, call.callId(), call.sessionId(), call.agentId(),
                call.principal(), call.tool(), argumentSummary.summarize(call.args()), applied, computed,
                policy.mode(), toolCalls, limit, before.taint(), policy.hash()));
    }
}
