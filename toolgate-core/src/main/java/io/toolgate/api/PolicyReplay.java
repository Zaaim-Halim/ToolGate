package io.toolgate.api;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.Replayer;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionEvent;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Runs recorded tool calls through a proposed policy and reports every decision that would change,
 * before the policy goes live. No model is called: the recorded calls are replayed, not the agent's
 * reasoning.
 *
 * <pre>{@code
 * PolicyReplay replay = PolicyReplay.builder(proposed).ownershipResolver(owners).build();
 * try (Stream<SessionEvent> history = jdbcSessionStore.events(lastWeek, now)) {
 *     System.out.print(replay.replay(history).summary());
 * }
 * }</pre>
 *
 * <p>Each session replays in order on fresh state, so a call the proposed policy denies no longer adds
 * taint, spends budget or adds to totals for the calls after it. A deferred call runs if history
 * released it. A call keeps its recorded result; one that runs only under the proposed policy is taken
 * to succeed, with its tool's labels. Ownership and path rules ask today's data and files. Result
 * rules are not replayed, because tool outputs are not stored; a recorded withheld output stays withheld.
 * Decisions compare as the policies computed them, by kind, rule and safe tool, so history recorded
 * in monitor mode works too.
 */
public final class PolicyReplay {

    private final ToolGatePolicy policy;
    private final OwnershipResolver owners;
    private final HostResolver hosts;

    private PolicyReplay(Builder builder) {
        this.policy = builder.policy;
        this.owners = builder.owners;
        this.hosts = builder.hosts;
    }

    /**
     * Starts a replay of a proposed policy.
     *
     * @param proposed the policy to try
     * @return a builder
     */
    public static Builder builder(ToolGatePolicy proposed) {
        return new Builder(proposed);
    }

    /**
     * Replays history. Events of one session must keep their order; sessions may interleave. Every
     * event is held in memory while the replay runs.
     *
     * @param history recorded session events, such as {@code JdbcSessionStore.events(from, to)}
     * @return what would change
     */
    public ReplayReport replay(Stream<SessionEvent> history) {
        return new Replayer(policy, owners, hosts).replay(history);
    }

    /** Settings for a replay. */
    public static final class Builder {

        private final ToolGatePolicy policy;
        private OwnershipResolver owners;
        private HostResolver hosts;

        private Builder(ToolGatePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        /**
         * Answers ownership rules with today's data, as the live gate does.
         *
         * @param resolver the application's resolver
         * @return this builder
         */
        public Builder ownershipResolver(OwnershipResolver resolver) {
            this.owners = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        /**
         * Resolves URL host names, as the live gate does when it has a resolver.
         *
         * @param resolver the application's resolver
         * @return this builder
         */
        public Builder hostResolver(HostResolver resolver) {
            this.hosts = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        /**
         * Builds the replay.
         *
         * @return the replay
         * @throws IllegalStateException if the policy uses ownership rules and no resolver was given
         */
        public PolicyReplay build() {
            if (owners == null && BuiltInEvaluators.usesOwnership(policy)) {
                throw new IllegalStateException("the policy uses sameTenant or ownedByPrincipal rules; set an ownershipResolver");
            }
            return new PolicyReplay(this);
        }
    }
}
