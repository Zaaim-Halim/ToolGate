package io.toolgate.engine;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.Budget;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.ArgumentSummary;
import io.toolgate.audit.AuditChannel;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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

    private final ToolGatePolicy policy;
    private final DecisionEngine engine;
    private final SessionStore sessions;
    private final AuditChannel audit;
    private final ArgumentSummary argumentSummary;
    private final Clock clock;

    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, SessionStore sessions,
                           AuditChannel audit, ArgumentSummary argumentSummary, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.engine = new DecisionEngine(policy, evaluators);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.argumentSummary = Objects.requireNonNull(argumentSummary, "argumentSummary");
        this.clock = Objects.requireNonNull(clock, "clock");
        Instant loadedAt = clock.instant();
        audit.submit(() -> new AuditEvent.PolicyLoaded(loadedAt, policy.hash(), policy.mode(), policy.agents().size()));
    }

    /** A gate without audit, for tests of decisions alone. */
    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, SessionStore sessions) {
        this(policy, evaluators, sessions, AuditChannel.NONE, ArgumentSummary.typesOnly(), Clock.systemUTC());
    }

    @Override
    public Decision check(ToolCall call) {
        Objects.requireNonNull(call, "call");
        if (policy.mode() == Mode.OFF) {
            return new Decision.Allow(BuiltInRules.MODE_OFF);
        }
        try {
            return decideAndRecord(call);
        } catch (RuntimeException storeFailure) {
            // Evaluators cannot throw past the engine, so this is the store. Without session state
            // every later call would be decided blind, so the run ends rather than this call alone.
            var ended = new Decision.Terminate(BuiltInRules.SESSION_UNAVAILABLE, "session store failed: " + storeFailure);
            audit(call, ended, ended, 0);
            return ended;
        }
    }

    @Override
    public long droppedAuditEvents() {
        return audit.dropped();
    }

    @Override
    public void close() {
        audit.close();
    }

    private Decision decideAndRecord(ToolCall call) {
        for (int attempt = 0; attempt < MAX_APPEND_ATTEMPTS; attempt++) {
            SessionState state = sessions.state(call.sessionId());
            Evaluation evaluation = decide(call, state);
            Decision decision = evaluation.effective();
            var event = new CallDecided(call.sessionId(), call.callId(), call.agentId(), call.principal(), call.tool(),
                    call.args(), decision, evaluation.computed(), policy.hash(), call.at());
            if (decision instanceof Decision.Terminate) {
                sessions.append(event);
                audit(call, decision, evaluation.computed(), state.apply(event).toolCalls());
                return decision;
            }
            if (sessions.append(event, state.version())) {
                audit(call, decision, evaluation.computed(), state.apply(event).toolCalls());
                return decision;
            }
        }
        var contention = new Decision.Deny(BuiltInRules.SESSION_CONTENTION,
                "session " + call.sessionId() + " changed on each of " + MAX_APPEND_ATTEMPTS + " attempts");
        audit(call, contention, contention, 0);
        return contention;
    }

    /** The engine's evaluation, with a downgrade replaced by its safe call's decision when that is stricter. */
    private Evaluation decide(ToolCall call, SessionState state) {
        Evaluation evaluation = engine.evaluate(call, state);
        if (!(evaluation.effective() instanceof Decision.Downgrade downgrade)) {
            return evaluation;
        }
        return new Evaluation(checkSafeCall(call, state, downgrade), evaluation.computed());
    }

    private Decision checkSafeCall(ToolCall call, SessionState state, Decision.Downgrade downgrade) {
        // The safe tool is checked like any other call, once, against the same state, so a downgrade
        // can never reach a tool the agent may not call. Its own decision wins unless it allows.
        Decision safe = engine.evaluate(call.withTool(downgrade.safeTool(), downgrade.safeArgs()), state).effective();
        return switch (safe) {
            case Decision.Allow allowed -> downgrade;
            case Decision.Downgrade chained -> new Decision.Deny(BuiltInRules.DOWNGRADE_CHAIN,
                    "safe tool " + downgrade.safeTool() + " would itself be downgraded to " + chained.safeTool());
            case Decision.Deny denied -> denied;
            case Decision.Defer deferred -> deferred;
            case Decision.Terminate terminated -> terminated;
        };
    }

    /** Queues the audit event; the time is taken now, and the arguments are summarized off this thread. */
    private void audit(ToolCall call, Decision applied, Decision computed, int toolCalls) {
        Instant decidedAt = clock.instant();
        int limit = policy.agent(call.agentId()).map(agent -> agent.budget().toolCalls())
                .orElse(Budget.UNLIMITED.toolCalls());
        audit.submit(() -> new AuditEvent.Decided(decidedAt, call.callId(), call.sessionId(), call.agentId(),
                call.principal(), call.tool(), argumentSummary.summarize(call.args()), applied, computed,
                policy.mode(), toolCalls, limit, policy.hash()));
    }
}
