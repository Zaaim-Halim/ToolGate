package io.toolgate.api;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.DefaultToolGate;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.SessionStore;
import java.util.Objects;

/**
 * The decision point between the model and every tool: the model proposes, ToolGate decides.
 *
 * <p>Adapters call {@link #check} for each tool call before the tool runs and apply the returned
 * {@link Decision}. Implementations are thread-safe; parallel calls in one session are checked
 * against consistent session state, so they cannot overshoot a budget together.
 */
public interface ToolGate {

    /**
     * Decides whether a call may run, and records the decision in the call's session.
     *
     * <p>Never throws for a call it cannot decide: anything it cannot evaluate is denied.
     */
    Decision check(ToolCall call);

    /** Starts building a gate that enforces {@code policy}. */
    static Builder builder(ToolGatePolicy policy) {
        return new Builder(policy);
    }

    /** Builds a {@link ToolGate}; every setting except the policy has a default. */
    final class Builder {

        private final ToolGatePolicy policy;
        private SessionStore sessionStore;

        private Builder(ToolGatePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        /**
         * Where session state is kept. Defaults to an in-memory store, which is correct only when
         * every call of a session reaches the same instance.
         */
        public Builder sessionStore(SessionStore store) {
            this.sessionStore = Objects.requireNonNull(store, "sessionStore");
            return this;
        }

        /** Builds the gate. */
        public ToolGate build() {
            return DefaultToolGate.withBuiltInRules(
                    policy, sessionStore != null ? sessionStore : new InMemorySessionStore());
        }
    }
}
