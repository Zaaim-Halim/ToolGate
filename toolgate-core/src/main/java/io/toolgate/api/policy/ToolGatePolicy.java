package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Effect;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import io.toolgate.policy.PolicyHash;
import io.toolgate.rules.ArgumentChecks;
import io.toolgate.validate.OutputPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 *     .tool("readTicket", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
 *     .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(READ))
 *     .tool("replyToCustomer", t -> t.labels(EXTERNAL_SINK))
 *     .tool("draftReply", t -> t.effect(REVERSIBLE))
 *     .agent("ticket-triage", a -> a
 *         .allow("readTicket", "lookupCustomer", "replyToCustomer", "draftReply", "mcp:jira/search_issues")
 *         .downgrade("replyToCustomer", "draftReply")
 *         .budget(b -> b.toolCalls(40)))
 *     .build();
 * }</pre>
 */
public final class ToolGatePolicy {

    /** How long a headless agent's deferred call waits for a reviewer, unless the agent says otherwise. */
    public static final Duration HEADLESS_APPROVAL_EXPIRY = Duration.ofHours(24);

    /** How long an interactive agent's deferred call waits for the user, unless the agent says otherwise. */
    public static final Duration INTERACTIVE_APPROVAL_EXPIRY = Duration.ofMinutes(10);

    /** Denials a session may collect before its next call ends the run, unless the policy says otherwise. */
    public static final int DEFAULT_DENIAL_BUDGET = 5;

    private final Mode mode;
    private final Map<String, Mode> ruleModes;
    private final int denialBudget;
    private final Set<String> mcpServers;
    private final Map<String, AgentPolicy> agents;
    private final Map<ToolId, ToolPolicy> tools;
    private final Map<ToolId, String> pins;
    private final String hash;

    private ToolGatePolicy(Builder builder) {
        this.mode = builder.mode;
        this.ruleModes = Map.copyOf(builder.ruleModes);
        this.denialBudget = builder.defaults.denialBudget;
        this.mcpServers = Set.copyOf(builder.mcpServers);
        this.agents = Map.copyOf(builder.agents);
        this.tools = Map.copyOf(builder.tools);
        this.pins = Map.copyOf(builder.pins);
        this.hash = PolicyHash.of(mode, ruleModes, denialBudget, mcpServers, tools, pins, agents);
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
     * What a tool does with data: as the policy describes it, or the default for its source.
     *
     * @param tool the tool
     * @return the policy's description, {@link ToolPolicy#MCP_DEFAULT} for an undescribed MCP tool,
     *         or {@link ToolPolicy#LOCAL_DEFAULT}
     */
    public ToolPolicy tool(ToolId tool) {
        ToolPolicy described = tools.get(tool);
        if (described != null) {
            return described;
        }
        return tool.isLocal() ? ToolPolicy.LOCAL_DEFAULT : ToolPolicy.MCP_DEFAULT;
    }

    /**
     * Every tool the policy describes.
     *
     * @return descriptions by tool
     */
    public Map<ToolId, ToolPolicy> tools() {
        return tools;
    }

    /**
     * The definition a tool must still have, if the policy pins it.
     *
     * @param tool the tool
     * @return its pin, from {@link ToolDefinition#sha256()}, or empty if the tool is not pinned
     */
    public Optional<String> pin(ToolId tool) {
        return Optional.ofNullable(pins.get(tool));
    }

    /**
     * Every pinned tool.
     *
     * @return pins by tool
     */
    public Map<ToolId, String> pins() {
        return pins;
    }

    /**
     * Identifies this exact policy in audit events and replay.
     *
     * <p>Computed from a canonical form, so it does not depend on the order in which the policy was
     * built, and two JVMs running the same policy agree on it.
     *
     * @return {@code sha256:} followed by 64 hex digits
     */
    public String hash() {
        return hash;
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
        private final Map<ToolId, ToolPolicy> tools = new LinkedHashMap<>();
        private final Map<ToolId, String> pins = new LinkedHashMap<>();

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
         * Describes what one tool does with data. Each tool may be described once. Labels here
         * override annotations in code, so a security team can correct a developer's labels.
         *
         * <p>An undescribed local tool has no labels; an undescribed MCP tool is untrusted content,
         * since a third party writes its output. Either is irreversible unless its effect is given.
         *
         * @param toolReference the tool, as in {@code sendEmail} or {@code mcp:jira/search_issues}
         * @param configurer    sets labels and effect
         * @return this builder
         */
        public Builder tool(String toolReference, Consumer<ToolBuilder> configurer) {
            ToolId tool;
            try {
                tool = ToolId.parse(toolReference);
            } catch (IllegalArgumentException malformed) {
                throw new InvalidPolicyException("tool '" + toolReference + "': " + malformed.getMessage());
            }
            if (tools.containsKey(tool)) {
                throw new InvalidPolicyException("tool " + tool + " is described twice");
            }
            var builder = new ToolBuilder(tool);
            configurer.accept(builder);
            tools.put(tool, builder.build());
            if (builder.pin != null) {
                pins.put(tool, builder.pin);
            }
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

    /** Describes one tool. */
    public static final class ToolBuilder {

        private final ToolId tool;
        private Set<Label> labels;
        private Effect effect = Effect.IRREVERSIBLE;
        private String pin;

        private ToolBuilder(ToolId tool) {
            this.tool = tool;
        }

        /**
         * The tool's data labels, replacing its default ones.
         *
         * @param newLabels the labels; none at all is allowed, and clears an MCP tool's default
         * @return this tool builder
         */
        public ToolBuilder labels(Label... newLabels) {
            this.labels = Set.of(newLabels);
            return this;
        }

        /**
         * Whether the tool's action can be undone. Without this, the tool is irreversible.
         *
         * @param newEffect the effect
         * @return this tool builder
         */
        public ToolBuilder effect(Effect newEffect) {
            this.effect = Objects.requireNonNull(newEffect, "effect");
            return this;
        }

        /**
         * Pins the tool's definition, its description and input schema, as reviewed. Once the
         * definition changes, such as after an MCP server's update, every call to the tool is denied
         * until the policy is updated with the new pin. The gate reads current definitions from a
         * {@link io.toolgate.spi.ToolCatalog}, which it then requires.
         *
         * @param reviewedPin {@link ToolDefinition#sha256()} of the definition as reviewed
         * @return this tool builder
         */
        public ToolBuilder pin(String reviewedPin) {
            Objects.requireNonNull(reviewedPin, "pin");
            if (!ToolDefinition.isPin(reviewedPin)) {
                throw new InvalidPolicyException("tool " + tool + ": pin must be sha256: and 64 lowercase hex digits, was '"
                        + reviewedPin + "'");
            }
            if (pin != null) {
                throw new InvalidPolicyException("tool " + tool + " is pinned twice");
            }
            this.pin = reviewedPin;
            return this;
        }

        private ToolPolicy build() {
            Set<Label> resolved = labels != null
                    ? labels
                    : (tool.isLocal() ? ToolPolicy.LOCAL_DEFAULT : ToolPolicy.MCP_DEFAULT).labels();
            return new ToolPolicy(resolved, effect);
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
        private final Map<ToolId, ToolId> downgrades = new LinkedHashMap<>();
        private final List<TaintRule> taintRules = new ArrayList<>();
        private final Map<ToolId, Set<String>> requiredRoles = new LinkedHashMap<>();
        private final List<ResultRule> resultRules = new ArrayList<>();
        private Budget budget = Budget.UNLIMITED;
        private boolean interactive;
        private TaintAction onTrifecta;
        private Duration approvalExpiry;
        private Set<String> reviewers = Set.of();
        private PendingTaintRule pending;

        private AgentBuilder(String id) {
            this.id = id;
        }

        /**
         * Whether a person can be present to confirm deferred calls. Defaults to {@code false}:
         * most agents run in backends with nobody watching. It sets the trifecta's default action.
         *
         * @param present {@code true} for a chat assistant with a signed-in user
         * @return this agent builder
         */
        public AgentBuilder interactive(boolean present) {
            this.interactive = present;
            return this;
        }

        /**
         * Runs a safe tool instead of {@code tool} whenever a taint rule objects to it, such as a
         * draft instead of a send. The safe tool gets the same arguments and is checked like any
         * other call. Argument rules still deny outright: a draft to an outside address is no safer.
         *
         * @param tool     the tool to replace
         * @param safeTool the tool to run instead; it must not be replaced itself
         * @return this agent builder
         */
        public AgentBuilder downgrade(String tool, String safeTool) {
            ToolId from = parse(tool);
            if (downgrades.putIfAbsent(from, parse(safeTool)) != null) {
                throw new InvalidPolicyException("agent '" + id + "': " + from + " is downgraded twice");
            }
            return this;
        }

        /**
         * What the trifecta rule does when no downgrade applies. Defaults to
         * {@link TaintAction#BLOCK} for headless agents and {@link TaintAction#DEFER} for
         * interactive ones.
         *
         * @param action the action
         * @return this agent builder
         */
        public AgentBuilder onTrifecta(TaintAction action) {
            this.onTrifecta = Objects.requireNonNull(action, "action");
            return this;
        }

        /**
         * How long a deferred call waits for a decision before it expires. Defaults to
         * {@link #HEADLESS_APPROVAL_EXPIRY} for headless agents and
         * {@link #INTERACTIVE_APPROVAL_EXPIRY} for interactive ones, whose user is right there.
         *
         * @param expiry a positive duration
         * @return this agent builder
         */
        public AgentBuilder approvalExpiry(Duration expiry) {
            Objects.requireNonNull(expiry, "expiry");
            if (expiry.isNegative() || expiry.isZero()) {
                throw new InvalidPolicyException("agent '" + id + "': approvalExpiry must be positive, was " + expiry);
            }
            this.approvalExpiry = expiry;
            return this;
        }

        /**
         * Lets principals with one of these roles approve or reject this agent's deferred calls.
         * Without it, nobody may, fail closed: deferred calls can then only expire, except in an
         * interactive agent, where the signed-in user confirms their own call. In a headless agent
         * a reviewer may never approve their own call, whatever their roles.
         *
         * @param roles roles, any one of which is enough
         * @return this agent builder
         */
        public AgentBuilder reviewers(String... roles) {
            if (roles.length == 0 || Arrays.stream(roles).anyMatch(role -> role == null || role.isBlank())) {
                throw new InvalidPolicyException("agent '" + id + "': reviewers needs non-blank roles");
            }
            if (!reviewers.isEmpty()) {
                throw new InvalidPolicyException("agent '" + id + "': reviewers is set twice");
            }
            this.reviewers = Set.copyOf(Arrays.asList(roles));
            return this;
        }

        /**
         * Lets only principals with one of these roles call a tool, such as refunds for finance.
         * Checked right after the allow-list, before any lookup.
         *
         * @param tool  the tool
         * @param roles roles, any one of which is enough
         * @return this agent builder
         */
        public AgentBuilder requireRole(String tool, String... roles) {
            ToolId target = parse(tool);
            if (roles.length == 0 || Arrays.stream(roles).anyMatch(role -> role == null || role.isBlank())) {
                throw new InvalidPolicyException("agent '" + id + "': requireRole(" + target + ") needs non-blank roles");
            }
            if (requiredRoles.putIfAbsent(target, Set.copyOf(Arrays.asList(roles))) != null) {
                throw new InvalidPolicyException("agent '" + id + "': " + target + " has requireRole twice");
            }
            return this;
        }

        /**
         * Checks what a tool returned before the model reads it. Every value the path reaches must
         * pass the check, or the whole output is withheld and the model is told the result is not
         * available. Withheld output does not taint the session and counts as a denial.
         *
         * <p>An output that does not have the path's shape, such as a missing field, an error, or
         * plain text, is withheld too: it cannot be checked. An empty array along the path is a
         * normal empty result and passes. Several rules on one tool must all pass.
         *
         * @param tool  the tool
         * @param path  field names, dots and {@code [*]}, such as {@code items[*].tenantId}
         * @param check {@link ResultRules#matchesTenant()}, {@link ResultRules#matchesPrincipal()},
         *              {@link ArgumentRules#sameTenant} or {@link ArgumentRules#ownedByPrincipal}
         * @return this agent builder
         */
        public AgentBuilder result(String tool, String path, ResultCheck check) {
            try {
                OutputPath.parse(path);
                resultRules.add(new ResultRule(parse(tool), path, check));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new InvalidPolicyException("agent '" + id + "': " + invalid.getMessage());
            }
            return this;
        }

        /**
         * Starts an extra taint rule: once the session holds every given label, calls to
         * {@code tool} get the action given to {@link PendingTaintRule#then}.
         *
         * @param tool   the tool
         * @param first  a source label
         * @param others more source labels, all of which the session must hold
         * @return the rule, to complete with {@code then}
         */
        public PendingTaintRule whenTainted(String tool, Label first, Label... others) {
            requireNoPendingRule();
            var labels = new LinkedHashSet<Label>();
            labels.add(Objects.requireNonNull(first, "label"));
            labels.addAll(Arrays.asList(others));
            pending = new PendingTaintRule(this, parse(tool), labels);
            return pending;
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
                String kind = ArgumentRules.kind(Objects.requireNonNull(rule, "rule"));
                if (existing.stream().map(ArgumentRules::kind).anyMatch(kind::equals)) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument + "' gives "
                            + kind + " twice");
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

        private ToolId parse(String toolReference) {
            try {
                return ToolId.parse(toolReference);
            } catch (IllegalArgumentException malformed) {
                throw new InvalidPolicyException("agent '" + id + "': " + malformed.getMessage());
            }
        }

        private void requireNoPendingRule() {
            if (pending != null) {
                throw new InvalidPolicyException("agent '" + id + "': whenTainted(" + pending.tool
                        + ", ...) has no action; finish it with then(BLOCK) or then(DEFER)");
            }
        }

        private void requireAllowed(ToolId tool, String role) {
            if (!allowedTools.contains(tool)) {
                throw new InvalidPolicyException("agent '" + id + "': " + role + " " + tool
                        + " is not on the agent's allow-list");
            }
        }

        private AgentPolicy build() {
            requireNoPendingRule();
            downgrades.forEach((tool, safeTool) -> {
                requireAllowed(tool, "downgraded tool");
                requireAllowed(safeTool, "safe tool");
                if (tool.equals(safeTool)) {
                    throw new InvalidPolicyException("agent '" + id + "': " + tool + " is downgraded to itself");
                }
                if (downgrades.containsKey(safeTool)) {
                    throw new InvalidPolicyException("agent '" + id + "': safe tool " + safeTool
                            + " is itself downgraded; a downgrade must end at a safe tool");
                }
            });
            taintRules.forEach(rule -> requireAllowed(rule.tool(), "taint rule tool"));
            requiredRoles.keySet().forEach(tool -> requireAllowed(tool, "role-restricted tool"));
            resultRules.forEach(rule -> {
                requireAllowed(rule.tool(), "result-checked tool");
                String resourceType = switch (rule.check()) {
                    case ArgumentRule.SameTenant(String type) -> type;
                    case ArgumentRule.OwnedByPrincipal(String type) -> type;
                    case ResultCheck.MatchesTenant matches -> "-";
                    case ResultCheck.MatchesPrincipal matches -> "-";
                };
                if (resourceType.isBlank()) {
                    throw new InvalidPolicyException("agent '" + id + "': " + rule.ruleId() + " needs a resource type");
                }
            });
            argumentRules.forEach((argument, rules) -> {
                if (!allowedTools.contains(argument.tool())) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument
                            + "' targets " + argument.tool() + ", which the agent is not allowed to call");
                }
                try {
                    ArgumentChecks.validate(rules);
                } catch (IllegalArgumentException invalid) {
                    throw new InvalidPolicyException("agent '" + id + "': rule '" + argument + "': "
                            + invalid.getMessage());
                }
            });
            TaintAction trifecta = onTrifecta != null ? onTrifecta : (interactive ? TaintAction.DEFER : TaintAction.BLOCK);
            Duration expiry = approvalExpiry != null
                    ? approvalExpiry
                    : (interactive ? INTERACTIVE_APPROVAL_EXPIRY : HEADLESS_APPROVAL_EXPIRY);
            return new AgentPolicy(id, allowedTools, budget, argumentRules, interactive, downgrades, trifecta, taintRules,
                    requiredRoles, resultRules, expiry, reviewers);
        }
    }

    /** A taint rule waiting for its action. */
    public static final class PendingTaintRule {

        private final AgentBuilder agent;
        private final ToolId tool;
        private final Set<Label> labels;

        private PendingTaintRule(AgentBuilder agent, ToolId tool, Set<Label> labels) {
            this.agent = agent;
            this.tool = tool;
            this.labels = labels;
        }

        /**
         * Completes the rule.
         *
         * @param action what happens to the call, unless the agent downgrades the tool
         * @return the agent builder, to continue
         */
        public AgentBuilder then(TaintAction action) {
            try {
                agent.taintRules.add(new TaintRule(tool, labels, action));
            } catch (IllegalArgumentException invalid) {
                throw new InvalidPolicyException("agent '" + agent.id + "': " + invalid.getMessage());
            } finally {
                agent.pending = null;
            }
            return agent;
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
