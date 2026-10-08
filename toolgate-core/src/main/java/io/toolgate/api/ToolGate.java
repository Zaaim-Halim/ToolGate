package io.toolgate.api;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.DefaultToolGate;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.HostResolver;
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
     * <p>Fails closed instead of throwing: a rule that cannot be evaluated denies the call, and a
     * failing session store ends the run, because without session state no budget can be checked.
     *
     * @param call the call the model asked for
     * @return the decision to apply
     * @throws NullPointerException if {@code call} is null
     */
    Decision check(ToolCall call);

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
        private HostResolver hostResolver;

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
         * Builds the gate.
         *
         * @return a thread-safe gate
         */
        public ToolGate build() {
            SessionStore store = sessionStore != null ? sessionStore : new InMemorySessionStore();
            return hostResolver != null
                    ? DefaultToolGate.withBuiltInRules(policy, store, hostResolver)
                    : DefaultToolGate.withBuiltInRules(policy, store);
        }
    }
}
