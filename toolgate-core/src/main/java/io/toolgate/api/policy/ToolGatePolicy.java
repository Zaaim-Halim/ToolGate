package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Mode;
import io.toolgate.api.ToolId;
import io.toolgate.rules.UrlArgumentRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A validated, immutable policy: deny by default, allow what is listed.
 *
 * <pre>{@code
 * ToolGatePolicy policy = ToolGatePolicy.builder()
 *     .mode(Mode.ENFORCE)
 *     .defaults(d -> d.denialBudget(5))
 *     .mcpServers("jira")
 *     .agent("ticket-triage", a -> a
 *         .allow("readTicket", "lookupCustomer", "fetch", "mcp:jira/search_issues")
 *         .rule("fetch.url", hosts("*.vendor.example"), schemes("https"))
 *         .budget(b -> b.toolCalls(40)))
 *     .build();
 * }</pre>
 */
public final class ToolGatePolicy {

    /** Denials a session may collect before its next call ends the run, unless the policy says otherwise. */
    public static final int DEFAULT_DENIAL_BUDGET = 5;

    private final Mode mode;
    private final Map<String, Mode> ruleModes;
    private final int denialBudget;
    private final Set<String> mcpServers;
    private final Map<String, AgentPolicy> agents;

    private ToolGatePolicy(Builder builder) {
        this.mode = builder.mode;
        this.ruleModes = Map.copyOf(builder.ruleModes);
        this.denialBudget = builder.defaults.denialBudget;
        this.mcpServers = Set.copyOf(builder.mcpServers);
        this.agents = Map.copyOf(builder.agents);
    }

    /**
     * Starts a policy in {@link Mode#ENFORCE} with no agents, so it denies every call.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The policy-wide mode.
     *
     * @return the mode every rule runs in unless set per rule
     */
    public Mode mode() {
        return mode;
    }

    /**
     * The mode one rule runs in: its own setting, or the policy-wide mode. A policy that is
     * {@link Mode#OFF} turns every rule off.
     *
     * @param ruleId the rule
     * @return the mode the rule runs in
     */
    public Mode modeFor(String ruleId) {
        return mode == Mode.OFF ? Mode.OFF : ruleModes.getOrDefault(ruleId, mode);
    }

    /**
     * Denials a session may collect before its next call ends the run. Like {@link Budget}, it
     * counts per session and never resets.
     *
     * @return the denial budget
     */
    public int denialBudget() {
        return denialBudget;
    }

    /**
     * MCP servers whose tools may be allowed at all.
     *
     * @return the server names
     */
    public Set<String> mcpServers() {
        return mcpServers;
    }

    /**
     * The rules for one agent.
     *
     * @param agentId the agent
     * @return its rules, or empty if the policy does not define it
     */
    public Optional<AgentPolicy> agent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /**
     * Every agent the policy defines.
     *
     * @return the agents' rules by agent ID
     */
    public Map<String, AgentPolicy> agents() {
        return agents;
    }

    /** Collects a policy and validates all of it in {@link #build}. */
    public static final class Builder {

        private Mode mode = Mode.ENFORCE;
        private final Map<String, Mode> ruleModes = new HashMap<>();
        private final Defaults defaults = new Defaults();
        private final Set<String> mcpServers = new LinkedHashSet<>();
        private final Map<String, AgentPolicy> agents = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Sets the policy-wide mode.
         *
         * @param newMode the mode
         * @return this builder
         */
        public Builder mode(Mode newMode) {
            this.mode = Objects.requireNonNull(newMode, "mode");
            return this;
        }

        /**
         * Runs one built-in rule in its own mode, for example monitoring a new rule while the rest
         * of the policy is enforced.
         *
         * <p>A single rule cannot be turned {@link Mode#OFF}: a skipped rule lets calls through
         * without a trace, and some rules guard others, as an unknown agent has no budgets to check.
         * {@code OFF} is only a policy-wide emergency switch.
         *
         * @param ruleId one of {@link BuiltInRules#CONFIGURABLE}
         * @param ruleMode {@link Mode#MONITOR} or {@link Mode#ENFORCE}
         * @return this builder
         */
        public Builder ruleMode(String ruleId, Mode ruleMode) {
            if (!BuiltInRules.CONFIGURABLE.contains(ruleId)) {
                throw new InvalidPolicyException("unknown rule '" + ruleId + "'; known rules: "
                        + BuiltInRules.CONFIGURABLE);
            }
            if (Objects.requireNonNull(ruleMode, "mode") == Mode.OFF) {
                throw new InvalidPolicyException("rule '" + ruleId + "' cannot be OFF; use MONITOR, or turn the"
                        + " whole policy OFF in an emergency");
            }
            ruleModes.put(ruleId, ruleMode);
            return this;
        }

        /**
         * Sets values that apply to every agent.
         *
         * @param configurer sets the values
         * @return this builder
         */
        public Builder defaults(Consumer<Defaults> configurer) {
            configurer.accept(defaults);
            return this;
        }

        /**
         * Allows tools from these MCP servers to be listed at all; tools from others are always denied.
         *
         * @param servers the servers' names
         * @return this builder
         */
        public Builder mcpServers(String... servers) {
            Arrays.stream(servers).forEach(server -> mcpServers.add(requireNonBlank(server, "MCP server")));
            return this;
        }

        /**
         * Defines one agent. Each agent ID may be defined once.
         *
         * @param agentId    the agent's ID, as tool calls name it
         * @param configurer sets the agent's rules
         * @return this builder
         */
        public Builder agent(String agentId, Consumer<AgentBuilder> configurer) {
            requireNonBlank(agentId, "agent id");
            if (agents.containsKey(agentId)) {
                throw new InvalidPolicyException("agent '" + agentId + "' is defined twice");
            }
            var agent = new AgentBuilder(agentId);
            configurer.accept(agent);
            agents.put(agentId, agent.build());
            return this;
        }

        /**
         * Validates and builds the policy.
         *
         * @return the immutable policy
         * @throws InvalidPolicyException if any part of the policy is inconsistent
         */
        public ToolGatePolicy build() {
            agents.values().forEach(this::requireKnownMcpServers);
            return new ToolGatePolicy(this);
        }

        private void requireKnownMcpServers(AgentPolicy agent) {
            for (ToolId tool : agent.allowedTools()) {
                tool.mcpServer().filter(server -> !mcpServers.contains(server)).ifPresent(server -> {
                    throw new InvalidPolicyException("agent '" + agent.id() + "' allows " + tool
                            + ", but MCP server '" + server + "' is not in mcpServers");
                });
            }
        }
    }

    /** Values that apply to every agent. */
    public static final class Defaults {

        private int denialBudget = DEFAULT_DENIAL_BUDGET;

        private Defaults() {}

        /**
         * Denials a session may collect; the next call after that ends the run.
         *
         * @param denials at least one
         * @return these defaults
         */
        public Defaults denialBudget(int denials) {
            this.denialBudget = requirePositive(denials, "denialBudget");
            return this;
        }
    }

    /** Defines one agent's rules. */
    public static final class AgentBuilder {

        private final String id;
        private final Set<ToolId> allowedTools = new LinkedHashSet<>();
        private final Map<ToolArgument, List<ArgumentRule>> argumentRules = new LinkedHashMap<>();
        private Budget budget = Budget.UNLIMITED;

        private AgentBuilder(String id) {
            this.id = id;
        }

        /**
         * Allows tools by reference: a bare name such as {@code sendEmail} is the application's own
         * tool, and remote tools are named with their server, as in {@code mcp:jira/search_issues}.
         *
         * @param toolReferences the tools
         * @return this agent builder
         */
        public AgentBuilder allow(String... toolReferences) {
            for (String reference : toolReferences) {
                try {
                    allowedTools.add(ToolId.parse(reference));
                } catch (IllegalArgumentException malformed) {
                    throw new InvalidPolicyException("agent '" + id + "': " + malformed.getMessage());
                }
            }
            return this;
        }

        /**
         * Restricts the values of one argument of one allowed tool. Several calls for the same
         * argument add up, but each kind of rule may be given once per argument.
         *
         * <p>A call without the argument passes, since it sends nothing anywhere; a value of the
         * wrong type, including JSON null, is denied.
         *
         * @param target the tool and argument, as in {@code fetchSupplierPage.url}; split at the last dot
         * @param rules  rules from {@link ArgumentRules}
         * @return this agent builder
         */
        public AgentBuilder rule(String target, ArgumentRule... rules) {
            ToolArgument argument;
            try {
                argument = ToolArgument.parse(target);
            } catch (IllegalArgumentException malformed) {
                throw new InvalidPolicyException("agent '" + id + "': " + malformed.getMessage());
            }
            if (rules.length == 0) {
                throw new InvalidPolicyException("agent '" + id + "': rule '" + argument + "' lists no rules");
            }
            List<ArgumentRule> existing = argumentRules.computeIfAbsent(argument, ignored -> new ArrayList<>());
            for (ArgumentRule rule : rules) {
                var kind = ArgumentRules.kind(Objects.requireNonNull(rule, "rule"));
                if (existing.stream().map(ArgumentRules::kind).anyMatch(kind::equals)) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument + "' gives "
                            + kind.getSimpleName().toLowerCase(Locale.ROOT) + " twice");
                }
                existing.add(rule);
            }
            return this;
        }

        /**
         * Limits each of this agent's sessions.
         *
         * @param configurer sets the limits
         * @return this agent builder
         */
        public AgentBuilder budget(Consumer<BudgetBuilder> configurer) {
            var builder = new BudgetBuilder();
            configurer.accept(builder);
            this.budget = new Budget(builder.toolCalls);
            return this;
        }

        private AgentPolicy build() {
            argumentRules.forEach((argument, rules) -> {
                if (!allowedTools.contains(argument.tool())) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument
                            + "' targets " + argument.tool() + ", which the agent is not allowed to call");
                }
                try {
                    UrlArgumentRules.compile(rules);
                } catch (IllegalArgumentException invalid) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument + "': "
                            + invalid.getMessage());
                }
            });
            return new AgentPolicy(id, allowedTools, budget, argumentRules);
        }
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new InvalidPolicyException(name + " must be at least 1, was " + value);
        }
        return value;
    }

    /** Sets an agent's limits. */
    public static final class BudgetBuilder {

        private int toolCalls = Budget.UNLIMITED.toolCalls();

        private BudgetBuilder() {}

        /**
         * Tool calls each session may make; denied calls do not count.
         *
         * @param calls at least one
         * @return this budget builder
         */
        public BudgetBuilder toolCalls(int calls) {
            this.toolCalls = requirePositive(calls, "toolCalls");
            return this;
        }
    }
}
