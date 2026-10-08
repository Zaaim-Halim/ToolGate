package io.toolgate.engine;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallDecided.Outcome;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.util.List;
import java.util.Objects;

/**
 * Checks calls with the engine and records each applied decision in the session, optimistically:
 * if another call in the same session was recorded in between, the check runs again on the new
 * state, so parallel calls can never overshoot a budget together.
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

    public DefaultToolGate(ToolGatePolicy policy, List<Evaluator> evaluators, SessionStore sessions) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.engine = new DecisionEngine(policy, evaluators);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** A gate running every built-in rule. */
    public static ToolGate withBuiltInRules(ToolGatePolicy policy, SessionStore sessions) {
        return new DefaultToolGate(policy, BuiltInEvaluators.all(), sessions);
    }

    @Override
    public Decision check(ToolCall call) {
        Objects.requireNonNull(call, "call");
        if (policy.mode() == Mode.OFF) {
            return new Decision.Allow(BuiltInRules.MODE_OFF);
        }
        for (int attempt = 0; attempt < MAX_APPEND_ATTEMPTS; attempt++) {
            SessionState state = sessions.state(call.sessionId());
            Decision decision = decide(call, state);
            var event = new CallDecided(call.sessionId(), call.callId(), call.tool(), outcomeOf(decision), call.at());
            if (decision instanceof Decision.Terminate) {
                sessions.append(event);
                return decision;
            }
            if (sessions.append(event, state.version())) {
                return decision;
            }
        }
        return new Decision.Deny(BuiltInRules.SESSION_CONTENTION,
                "session " + call.sessionId() + " changed on each of " + MAX_APPEND_ATTEMPTS + " attempts");
    }

    private Decision decide(ToolCall call, SessionState state) {
        Decision decision = engine.evaluate(call, state).effective();
        if (!(decision instanceof Decision.Downgrade downgrade)) {
            return decision;
        }
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

    private static Outcome outcomeOf(Decision decision) {
        return switch (decision) {
            case Decision.Allow allowed -> Outcome.ALLOWED;
            case Decision.Deny denied -> Outcome.DENIED;
            case Decision.Defer deferred -> Outcome.DEFERRED;
            case Decision.Downgrade downgraded -> Outcome.DOWNGRADED;
            case Decision.Terminate terminated -> Outcome.TERMINATED;
        };
    }
}
