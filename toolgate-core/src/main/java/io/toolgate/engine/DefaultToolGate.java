package io.toolgate.engine;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalQuery;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.Execution;
import io.toolgate.api.Journey;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Provenance;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ReviewArgument;
import io.toolgate.api.ReviewItem;
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
import io.toolgate.spi.SessionEvent.CallResolved;
import io.toolgate.spi.SessionEvent.CallUnresolved;
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
    /** Decisions on one call rarely race; a few attempts settle any honest contention. */
    private static final int MAX_DECISION_ATTEMPTS = 16;
    private static final int MAX_SUMMARY_CHARS = 500;
    /** How many pages a history reads looking for calls the viewer may see, so a narrow viewer never scans everything. */
    private static final int MAX_HISTORY_PAGES = 20;

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
    private final SuperAdminWatch superAdminWatch;
    /** Observer calls that threw; counted with the dropped audit events, since both leave gaps. */
    private final AtomicLong failedObservations = new AtomicLong();
    private final ArgumentSummary argumentSummary;
    private final Clock clock;
    private final ToolCatalog catalog;

    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, ResultChecker results,
                           SessionStore sessions, ApprovalQueue approvals, AuditChannel audit,
                           List<DecisionObserver> observers, SuperAdminWatch superAdminWatch,
                           ArgumentSummary argumentSummary, Clock clock, ToolCatalog catalog) {
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
        this.superAdminWatch = Objects.requireNonNull(superAdminWatch, "superAdminWatch");
        this.argumentSummary = Objects.requireNonNull(argumentSummary, "argumentSummary");
        this.clock = Objects.requireNonNull(clock, "clock");
        Instant loadedAt = clock.instant();
        audit.submit(() -> new AuditEvent.PolicyLoaded(loadedAt, policy.hash(), policy.mode(), policy.agents().size()));
    }

    /** A gate without audit, for tests of decisions alone. */
    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, SessionStore sessions) {
        this(policy, evaluators, new ResultChecker(policy, BuiltInEvaluators.NO_OWNERS), sessions,
                new InMemoryApprovalQueue(), AuditChannel.NONE, List.of(), SuperAdminWatch.OFF, ArgumentSummary.typesOnly(), Clock.systemUTC(),
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
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal, String comment,
                            String digest) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(currentPrincipal, "currentPrincipal");
        if (comment != null) {
            requireNonBlank(comment, "comment");
        }
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall pending = pendingCall(approvalId);
            ToolCall call = pending.call();
            if (!currentPrincipal.id().equals(call.principal().id())) {
                throw new IllegalArgumentException("currentPrincipal " + currentPrincipal.id()
                        + " is not the call's principal " + call.principal().id());
            }
            if (pending.status() != ApprovalStatus.PENDING) {
                return new Approval.AlreadyDecided(pending.status());
            }
            Instant now = clock.instant();
            if (!now.isBefore(pending.expiresAt())) {
                return expire(pending) ? new Approval.Expired() : alreadyDecided(approvalId);
            }
            Optional<String> notPermitted = permissionProblem(call, reviewer);
            if (notPermitted.isPresent()) {
                return new Approval.NotPermitted(notPermitted.get());
            }
            if (digest != null && !digest.equals(digestOf(pending))) {
                return new Approval.NotPermitted("what was shown is not what would be approved; reload the call");
            }
            ApprovalAccess access = decidingAccess(call, reviewer);
            if (access == ApprovalAccess.SUPER_ADMIN && comment == null) {
                return new Approval.NotPermitted("a super admin must justify the decision with a comment");
            }
            if (approvedBy(pending, reviewer)) {
                return new Approval.NotPermitted("principal " + reviewer.id()
                        + " already approved this call; the next approval must come from another person");
            }
            var decision = new ApprovalDecision(ApprovalDecision.Kind.APPROVE, reviewer.id(), access, now, comment);
            if (!releasable(pending, decision)) {
                if (!approvals.replace(pending, pending.withDecision(decision, ApprovalStatus.PENDING))) {
                    continue;
                }
                watchSuperAdmin(access, reviewer, now);
                audit.submit(() -> new AuditEvent.ApprovalRecorded(now, approvalId, call.sessionId(), call.agentId(),
                        call.tool(), reviewer.id(), access, comment));
                return new Approval.AwaitingConfirmation("a super admin's approval needs a second person: another"
                        + " super admin, or a reviewer of the call's tenant");
            }
            ToolCall released = new ToolCall(call.callId(), currentPrincipal, call.agentId(), call.sessionId(),
                    call.tool(), call.args(), call.at());
            Optional<String> refusal = recheck(released, pending.deferral());
            ApprovalStatus outcome = refusal.isPresent() ? ApprovalStatus.REFUSED : ApprovalStatus.RELEASED;
            PendingCall decided = pending.withDecision(decision, outcome);
            if (!approvals.replace(pending, decided)) {
                continue;
            }
            watchSuperAdmin(access, reviewer, now);
            if (outcome == ApprovalStatus.RELEASED) {
                recordRelease(decided, released);
            }
            auditApproval(pending, outcome, reviewer.id(), access,
                    refusal.orElse(comment != null ? comment : "approved"));
            return refusal.<Approval>map(Approval.Refused::new)
                    .orElseGet(() -> new Approval.Released(released, approvalId));
        }
        throw new IllegalStateException("approval " + approvalId + " changed on each of " + MAX_DECISION_ATTEMPTS
                + " attempts");
    }

    @Override
    public boolean reject(String approvalId, Principal reviewer, String reason) {
        Objects.requireNonNull(reviewer, "reviewer");
        requireNonBlank(reason, "reason");
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall pending = pendingCall(approvalId);
            if (pending.status() != ApprovalStatus.PENDING || permissionProblem(pending.call(), reviewer).isPresent()) {
                return false;
            }
            Instant now = clock.instant();
            ApprovalAccess access = decidingAccess(pending.call(), reviewer);
            var decision = new ApprovalDecision(ApprovalDecision.Kind.REJECT, reviewer.id(), access, now, reason);
            if (approvals.replace(pending, pending.withDecision(decision, ApprovalStatus.REJECTED))) {
                watchSuperAdmin(access, reviewer, now);
                auditApproval(pending, ApprovalStatus.REJECTED, reviewer.id(), access, reason);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean cancel(String approvalId, Principal by, String reason) {
        Objects.requireNonNull(by, "by");
        requireNonBlank(reason, "reason");
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall pending = pendingCall(approvalId);
            if (pending.status() != ApprovalStatus.PENDING) {
                return false;
            }
            ApprovalAccess access;
            if (isOwn(pending.call(), by)) {
                access = ApprovalAccess.REQUESTER;
            } else if (holdsAny(by, policy.superAdmins())) {
                access = ApprovalAccess.SUPER_ADMIN;
            } else {
                return false;
            }
            Instant now = clock.instant();
            var decision = new ApprovalDecision(ApprovalDecision.Kind.CANCEL, by.id(), access, now, reason);
            if (approvals.replace(pending, pending.withDecision(decision, ApprovalStatus.CANCELLED))) {
                watchSuperAdmin(access, by, now);
                auditApproval(pending, ApprovalStatus.CANCELLED, by.id(), access, reason);
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Approval.Released> claim(String approvalId, String executor, Duration lease) {
        requireNonBlank(executor, "executor");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a lease must be positive");
        }
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall released = pendingCall(approvalId);
            if (released.status() != ApprovalStatus.RELEASED) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            var run = new Execution(executor, now, now.plus(lease), null, null);
            if (approvals.replace(released, released.withExecution(run, ApprovalStatus.EXECUTING))) {
                return Optional.of(new Approval.Released(released.call(), approvalId));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean finished(String approvalId, String executor, ToolResult result) {
        requireNonBlank(executor, "executor");
        Objects.requireNonNull(result, "result");
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall running = pendingCall(approvalId);
            Optional<Execution> run = running.run();
            boolean awaitingOutcome = running.status() == ApprovalStatus.EXECUTING || running.status() == ApprovalStatus.UNKNOWN;
            if (!awaitingOutcome || run.isEmpty() || !run.get().executor().equals(executor)
                    || run.get().finishedAt() != null) {
                return false;
            }
            Instant now = clock.instant();
            ApprovalStatus outcome = result instanceof ToolResult.Succeeded ? ApprovalStatus.EXECUTED : ApprovalStatus.FAILED;
            if (approvals.replace(running, running.withExecution(run.get().finished(now, outcomeSummary(result)), outcome))) {
                if (running.status() == ApprovalStatus.UNKNOWN) {
                    sessions.append(new CallResolved(running.call().sessionId(), approvalId, now));
                }
                // The audit gets no output or error text: either can carry data the log must not hold.
                auditApproval(running, outcome, executor, null,
                        outcome == ApprovalStatus.EXECUTED ? "the tool ran" : "the tool failed");
                return true;
            }
        }
        return false;
    }

    @Override
    public List<PendingCall> pending() {
        Instant now = clock.instant();
        return approvals.pending().stream().filter(call -> now.isBefore(call.expiresAt())).toList();
    }

    @Override
    public Optional<ReviewItem> reviewItem(String approvalId, Principal viewer) {
        Objects.requireNonNull(viewer, "viewer");
        PendingCall pending = pendingCall(approvalId);
        ToolCall call = pending.call();
        Optional<ApprovalAccess> access = access(call, viewer);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        if (isPrivileged(access.get())) {
            Instant at = clock.instant();
            audit.submit(() -> new AuditEvent.ApprovalViewed(at, approvalId, call.sessionId(), call.agentId(), call.tool(),
                    viewer.id(), access.get()));
        }
        return Optional.of(item(pending, viewer, access.get()));
    }

    @Override
    public Optional<Journey> journey(String approvalId, Principal viewer) {
        Objects.requireNonNull(viewer, "viewer");
        PendingCall pending = pendingCall(approvalId);
        ToolCall call = pending.call();
        Optional<ApprovalAccess> access = access(call, viewer);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        if (isPrivileged(access.get())) {
            Instant at = clock.instant();
            audit.submit(() -> new AuditEvent.ApprovalViewed(at, approvalId, call.sessionId(), call.agentId(), call.tool(),
                    viewer.id(), access.get()));
        }
        String shownReason = ReviewViews.show(approvalId, policy.agent(call.agentId()), call, pending.deferral()).reason();
        return Optional.of(Journeys.of(pending, sessions.events(call.sessionId()), shownReason));
    }

    /** One call as a viewer sees it, masked by its agent's review view, which may be gone after a redeploy. */
    private ReviewItem item(PendingCall pending, Principal viewer, ApprovalAccess access) {
        ToolCall call = pending.call();
        ReviewViews.Shown shown = ReviewViews.show(pending.approvalId(), policy.agent(call.agentId()), call,
                pending.deferral());
        List<Provenance> provenance = sessions.state(call.sessionId()).taint().entrySet().stream()
                .map(source -> new Provenance(source.getKey(), source.getValue().tool(), source.getValue().callId()))
                .toList();
        return new ReviewItem(pending.approvalId(), call.agentId(), call.tool(), shown.title(), call.principal(),
                pending.status(), pending.expiresAt(), pending.deferral().ruleId(), shown.reason(), shown.arguments(),
                provenance, pending.decisions(), access, mayTakeNextStep(pending, viewer), shown.digest());
    }

    /** Whether the viewer may take the step the call's status allows: decide it while pending, resolve it while unknown. */
    private boolean mayTakeNextStep(PendingCall pending, Principal viewer) {
        return switch (pending.status()) {
            case PENDING -> permissionProblem(pending.call(), viewer).isEmpty();
            case UNKNOWN -> mayResolve(pending.call(), viewer);
            default -> false;
        };
    }

    /** A reviewer of the call's tenant or a super admin, never for their own call. */
    private boolean mayResolve(ToolCall call, Principal by) {
        return !isOwn(call, by) && access(call, by)
                .filter(access -> access == ApprovalAccess.SUPER_ADMIN || access == ApprovalAccess.REVIEWER).isPresent();
    }

    /** The digest of a call's review view: the same for every viewer, since the view is the policy's. */
    private String digestOf(PendingCall pending) {
        return ReviewViews.show(pending.approvalId(), policy.agent(pending.call().agentId()), pending.call(),
                pending.deferral()).digest();
    }

    /** Super admins and auditors may read every tenant's calls, so their reading leaves a trace. */
    private static boolean isPrivileged(ApprovalAccess access) {
        return access == ApprovalAccess.SUPER_ADMIN || access == ApprovalAccess.AUDITOR;
    }

    @Override
    public List<PendingCall> withStatus(ApprovalStatus status) {
        return approvals.withStatus(Objects.requireNonNull(status, "status"));
    }

    @Override
    public List<ReviewItem> pending(Principal viewer) {
        Objects.requireNonNull(viewer, "viewer");
        var items = new ArrayList<ReviewItem>();
        ApprovalAccess strongest = null;
        for (PendingCall waiting : pending()) {
            Optional<ApprovalAccess> access = access(waiting.call(), viewer);
            if (access.isPresent()) {
                items.add(item(waiting, viewer, access.get()));
                if (isPrivileged(access.get()) && (strongest == null || access.get().compareTo(strongest) < 0)) {
                    strongest = access.get();
                }
            }
        }
        auditListing(viewer, strongest, items.size());
        return List.copyOf(items);
    }

    @Override
    public List<ReviewItem> history(Principal viewer, ApprovalQuery query) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(query, "query");
        var items = new ArrayList<ReviewItem>();
        ApprovalAccess strongest = null;
        // The queue cannot filter by what a viewer may see, so read pages until there are enough or none left.
        for (int page = 0; page < MAX_HISTORY_PAGES && items.size() < query.limit(); page++) {
            List<PendingCall> calls = approvals.find(query.statuses(), query.since(), query.limit(), page * query.limit());
            for (PendingCall call : calls) {
                Optional<ApprovalAccess> access = access(call.call(), viewer);
                if (access.isPresent() && items.size() < query.limit()) {
                    items.add(item(call, viewer, access.get()));
                    if (isPrivileged(access.get()) && (strongest == null || access.get().compareTo(strongest) < 0)) {
                        strongest = access.get();
                    }
                }
            }
            if (calls.size() < query.limit()) {
                break;
            }
        }
        auditListing(viewer, strongest, items.size());
        return List.copyOf(items);
    }

    /** One trace per listing by a super admin or auditor, not per call, so a screen that polls does not flood the log. */
    private void auditListing(Principal viewer, ApprovalAccess strongest, int count) {
        if (strongest != null) {
            Instant at = clock.instant();
            audit.submit(() -> new AuditEvent.ApprovalsListed(at, viewer.id(), strongest, count));
        }
    }

    @Override
    public Sweep sweep() {
        Instant now = clock.instant();
        int expired = 0;
        for (PendingCall call : approvals.pending()) {
            if (!now.isBefore(call.expiresAt()) && expire(call)) {
                expired++;
            }
        }
        int unknown = 0;
        for (PendingCall running : approvals.withStatus(ApprovalStatus.EXECUTING)) {
            Optional<Execution> run = running.run();
            if (run.isPresent() && !now.isBefore(run.get().leaseUntil()) && markUnknown(running, now)) {
                unknown++;
                auditApproval(running, ApprovalStatus.UNKNOWN, "toolgate", null, "the lease of " + run.get().executor()
                        + " ran out at " + run.get().leaseUntil() + " with no outcome; check whether the tool ran");
            }
        }
        return new Sweep(expired, unknown);
    }

    /**
     * Marks a run's outcome unknown. The session learns first, so the block on the tool can never be
     * missing while the queue says unknown; if the run finished meanwhile, the block is lifted again.
     */
    private boolean markUnknown(PendingCall running, Instant now) {
        String sessionId = running.call().sessionId();
        sessions.append(new CallUnresolved(sessionId, running.approvalId(), running.call().tool(), now));
        if (approvals.replace(running, running.withStatus(ApprovalStatus.UNKNOWN))) {
            return true;
        }
        sessions.append(new CallResolved(sessionId, running.approvalId(), now));
        return false;
    }

    @Override
    public boolean resolve(String approvalId, Principal by, boolean ran, String reason) {
        Objects.requireNonNull(by, "by");
        requireNonBlank(reason, "reason");
        for (int attempt = 0; attempt < MAX_DECISION_ATTEMPTS; attempt++) {
            PendingCall unknown = pendingCall(approvalId);
            ToolCall call = unknown.call();
            if (!mayResolve(call, by)) {
                return false;
            }
            ApprovalAccess access = access(call, by).orElseThrow();
            Instant now = clock.instant();
            if (unknown.status() != ApprovalStatus.UNKNOWN) {
                return repairUnresolved(unknown, now);
            }
            ApprovalStatus outcome = ran ? ApprovalStatus.EXECUTED : ApprovalStatus.FAILED;
            var decision = new ApprovalDecision(ApprovalDecision.Kind.RESOLVE, by.id(), access, now, reason);
            if (approvals.replace(unknown, unknown.withDecision(decision, outcome))) {
                sessions.append(new CallResolved(call.sessionId(), approvalId, now));
                watchSuperAdmin(access, by, now);
                auditApproval(unknown, outcome, by.id(), access, reason);
                return true;
            }
        }
        return false;
    }

    /** Lifts a block the session still holds for a call whose outcome the queue already knows. */
    private boolean repairUnresolved(PendingCall call, Instant now) {
        boolean known = call.status() == ApprovalStatus.EXECUTED || call.status() == ApprovalStatus.FAILED;
        String sessionId = call.call().sessionId();
        if (!known || !sessions.state(sessionId).unresolved().containsKey(call.approvalId())) {
            return false;
        }
        sessions.append(new CallResolved(sessionId, call.approvalId(), now));
        return true;
    }

    /**
     * Records the release in the session, after the queue says released, so a lost race records
     * nothing. If the session store fails, the call is refused instead: an executor must never run a
     * call whose spending the session does not know about.
     */
    private void recordRelease(PendingCall released, ToolCall call) {
        try {
            sessions.append(new CallReleased(call.sessionId(), released.approvalId(), call.callId(),
                    spent(call, new Decision.Allow(released.deferral().ruleId())), clock.instant()));
        } catch (RuntimeException storeFailure) {
            if (approvals.replace(released, released.withStatus(ApprovalStatus.REFUSED))) {
                auditApproval(released, ApprovalStatus.REFUSED, "toolgate", null,
                        "session store failed while recording the release: " + storeFailure);
            }
            throw storeFailure;
        }
    }

    /** Whether the approvals so far, with this one, release the call. */
    private boolean releasable(PendingCall pending, ApprovalDecision approval) {
        if (!policy.superAdminConfirmation()) {
            return true;
        }
        var approvalsSoFar = new ArrayList<ApprovalDecision>(pending.decisions());
        approvalsSoFar.add(approval);
        List<ApprovalDecision> approved = approvalsSoFar.stream()
                .filter(decision -> decision.kind() == ApprovalDecision.Kind.APPROVE).toList();
        // Anyone else who may decide could have released it alone; two different super admins are
        // the break-glass path.
        return approved.stream().anyMatch(decision -> decision.access() != ApprovalAccess.SUPER_ADMIN)
                || approved.stream().map(ApprovalDecision::by).distinct().count() >= 2;
    }

    private static boolean approvedBy(PendingCall pending, Principal reviewer) {
        return pending.decisions().stream().anyMatch(decision ->
                decision.kind() == ApprovalDecision.Kind.APPROVE && decision.by().equals(reviewer.id()));
    }

    private void watchSuperAdmin(ApprovalAccess access, Principal by, Instant at) {
        if (access == ApprovalAccess.SUPER_ADMIN) {
            superAdminWatch.decided(at, approvals).ifPresent(decisions -> audit.submit(() ->
                    new AuditEvent.SuperAdminBurst(at, decisions, superAdminWatch.window(), by.id())));
        }
    }

    /** What the queue keeps of a run's outcome: shortened, since it is for a person to read. */
    private static String outcomeSummary(ToolResult result) {
        String text = switch (result) {
            case ToolResult.Succeeded(ArgValue output) -> "returned " + ArgValueJson.write(output);
            case ToolResult.Failed(String message) -> "failed: " + message;
        };
        return text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "…";
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
    /**
     * The strongest reason a viewer may see a call, if any. A reviewer's role counts only in the
     * call's own tenant, or a reviewer of one customer could read and decide another's calls.
     */
    private Optional<ApprovalAccess> access(ToolCall call, Principal viewer) {
        if (holdsAny(viewer, policy.superAdmins())) {
            return Optional.of(ApprovalAccess.SUPER_ADMIN);
        }
        boolean reviewsThisAgent = policy.agent(call.agentId()).map(agent -> holdsAny(viewer, agent.reviewers())).orElse(false);
        if (reviewsThisAgent && viewer.tenant().equals(call.principal().tenant())) {
            return Optional.of(ApprovalAccess.REVIEWER);
        }
        if (holdsAny(viewer, policy.auditors())) {
            return Optional.of(ApprovalAccess.AUDITOR);
        }
        return isOwn(call, viewer) ? Optional.of(ApprovalAccess.REQUESTER) : Optional.empty();
    }

    /** Why a principal may not decide a call, if they may not. */
    private Optional<String> permissionProblem(ToolCall call, Principal reviewer) {
        Optional<AgentPolicy> agent = policy.agent(call.agentId());
        if (isOwn(call, reviewer)) {
            // The signed-in user confirms their own call in an interactive agent; anywhere else,
            // approving one's own call would make the review meaningless, for every role.
            return agent.map(AgentPolicy::interactive).orElse(false)
                    ? Optional.empty()
                    : Optional.of("principal " + reviewer.id() + " may not approve its own call");
        }
        Optional<ApprovalAccess> access = access(call, reviewer);
        if (access.isPresent()) {
            return switch (access.get()) {
                case SUPER_ADMIN, REVIEWER -> Optional.empty();
                case AUDITOR -> Optional.of("auditor " + reviewer.id() + " may see calls but not decide them");
                case REQUESTER -> Optional.of("principal " + reviewer.id() + " may not approve its own call");
            };
        }
        Set<String> reviewers = agent.map(AgentPolicy::reviewers).orElse(Set.of());
        if (reviewers.isEmpty()) {
            return Optional.of("agent " + call.agentId() + " names no reviewers, so its calls cannot be approved");
        }
        if (Collections.disjoint(reviewers, reviewer.roles())) {
            return Optional.of("reviewer " + reviewer.id() + " has none of the reviewer roles " + new TreeSet<>(reviewers));
        }
        return Optional.of("reviewer " + reviewer.id() + " is not in the call's tenant");
    }

    /** The access a decision is recorded under: the requester's own when confirming in an interactive agent. */
    private ApprovalAccess decidingAccess(ToolCall call, Principal reviewer) {
        return isOwn(call, reviewer) ? ApprovalAccess.REQUESTER : access(call, reviewer).orElseThrow();
    }

    /** IDs are unique only within a tenant, so a call is one's own only in one's own tenant. */
    private static boolean isOwn(ToolCall call, Principal viewer) {
        return viewer.id().equals(call.principal().id()) && viewer.tenant().equals(call.principal().tenant());
    }

    private static boolean holdsAny(Principal principal, Set<String> roles) {
        return !roles.isEmpty() && !Collections.disjoint(roles, principal.roles());
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
        if (!approvals.replace(pending, pending.withStatus(ApprovalStatus.EXPIRED))) {
            return false;
        }
        auditApproval(pending, ApprovalStatus.EXPIRED, "toolgate", null, "nobody decided before " + pending.expiresAt());
        return true;
    }

    private void auditApproval(PendingCall pending, ApprovalStatus status, String decidedBy, ApprovalAccess access,
                               String reason) {
        Instant at = clock.instant();
        ToolCall call = pending.call();
        audit.submit(() -> new AuditEvent.ApprovalDecided(at, pending.approvalId(), call.sessionId(), call.agentId(),
                call.tool(), status, decidedBy, access, reason));
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
