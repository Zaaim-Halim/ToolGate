package io.toolgate.engine;

import io.toolgate.api.ToolCall;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.SessionState;
import java.util.Objects;

/**
 * Everything an evaluator may look at. The session is a snapshot, so every evaluator of one check
 * sees the same state.
 *
 * @param call    the call being checked
 * @param policy  the whole policy
 * @param agent   the calling agent's rules; the engine denies calls from unknown agents before this exists
 * @param session the session's state before this call
 */
public record EvaluationContext(ToolCall call, ToolGatePolicy policy, AgentPolicy agent, SessionState session) {

    public EvaluationContext {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(session, "session");
    }
}
