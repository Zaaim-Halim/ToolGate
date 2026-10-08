package io.toolgate.api;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.ArgumentSummary;
import io.toolgate.audit.AsyncAuditDispatcher;
import io.toolgate.audit.SystemLoggerSink;
import io.toolgate.engine.DefaultToolGate;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.SessionStore;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/**
 * The decision point between the model and every tool: the model proposes, ToolGate decides.
 *
 * <p>Adapters call {@link #check} for each tool call before the tool runs and apply the returned
 * {@link Decision}. Implementations are thread-safe; parallel calls in one session are checked
 * against consistent session state, so they cannot overshoot a budget together.
 *
 * <p>Every decision is also sent to the audit sink, off the request path. {@link #close} delivers
 * the events still queued; call it when the application shuts down.
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
     * Audit events that never reached the sink, because its queue was full, it threw, or the event
     * came after {@link #close}. Worth a metric and an alert: enforcement is unaffected, but the
     * audit trail has gaps.
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
        private HostResolver hostResolver;
        private AuditSink auditSink;
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
         * Where audit events go. Defaults to one JSON line per event on the JDK logger
         * {@code toolgate.audit}, which Spring Boot routes to SLF4J.
         *
         * @param sink the sink, called from one background thread
         * @return this builder
         */
        public Builder auditSink(AuditSink sink) {
            this.auditSink = Objects.requireNonNull(sink, "auditSink");
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
         */
        public ToolGate build() {
            return new DefaultToolGate(
                    policy,
                    hostResolver != null
                            ? BuiltInEvaluators.forPolicy(policy, hostResolver)
                            : BuiltInEvaluators.forPolicy(policy),
                    sessionStore != null ? sessionStore : new InMemorySessionStore(),
                    new AsyncAuditDispatcher(auditSink != null ? auditSink : new SystemLoggerSink(),
                            AsyncAuditDispatcher.DEFAULT_CAPACITY),
                    argumentHashKey != null ? ArgumentSummary.keyed(argumentHashKey) : ArgumentSummary.typesOnly(),
                    clock);
        }
    }
}
