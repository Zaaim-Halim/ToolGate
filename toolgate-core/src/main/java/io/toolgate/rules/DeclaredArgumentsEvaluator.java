package io.toolgate.rules;

import io.toolgate.api.ObjectValue;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps argument rules and the tool's own definition in step, for tools an agent has argument rules
 * on. A call may only pass arguments the tool's schema declares, so the model cannot slip in an extra
 * parameter the policy never considered, such as {@code cc} beside a checked {@code to}. A rule on an
 * argument the schema does not declare would never fire, since a call without the argument passes,
 * so that call is denied too. A tool the catalog does not know is denied: nothing can be checked.
 */
final class DeclaredArgumentsEvaluator implements Evaluator {

    /** Names are chosen by the model, so only a few are quoted in the reason. */
    private static final int MAX_NAMES_IN_REASON = 5;

    /** Ruled argument names, by agent and tool. */
    private final Map<String, Map<ToolId, Set<String>>> ruled = new HashMap<>();

    DeclaredArgumentsEvaluator(ToolGatePolicy policy) {
        // Bound arguments count as ruled: a bound tool may not take an argument its definition does not declare.
        policy.agents().values().forEach(agent -> ruled.put(agent.id(), Stream.concat(
                        agent.argumentRules().keySet().stream(), agent.bindings().keySet().stream())
                .collect(Collectors.groupingBy(ToolArgument::tool,
                        Collectors.mapping(ToolArgument::name, Collectors.toUnmodifiableSet())))));
    }

    /**
     * Whether a policy has any argument rule, and so needs a tool catalog.
     *
     * @param policy the policy
     * @return {@code true} if any agent has an argument rule
     */
    static boolean needed(ToolGatePolicy policy) {
        return policy.agents().values().stream()
                .anyMatch(agent -> !agent.argumentRules().isEmpty() || !agent.bindings().isEmpty());
    }

    @Override
    public String id() {
        return BuiltInRules.ARGUMENTS_DECLARED;
    }

    @Override
    public Stage stage() {
        return Stage.STRUCTURE;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        ToolId tool = context.call().tool();
        Set<String> ruledNames = ruled.getOrDefault(context.agent().id(), Map.of()).get(tool);
        if (ruledNames == null) {
            return Verdict.PASS;
        }
        Optional<ToolDefinition> definition = context.definitions().definition(tool);
        if (definition.isEmpty()) {
            return deny(tool + " has argument rules, but the tool catalog has no definition for it");
        }
        Set<String> declared = declaredNames(definition.get().inputSchema());
        var unruled = new TreeSet<>(ruledNames);
        unruled.removeAll(declared);
        if (!unruled.isEmpty()) {
            return deny("the policy has rules on " + names(unruled) + ", which " + tool + "'s definition does not declare");
        }
        var undeclared = new TreeSet<>(context.call().args().fields().keySet());
        undeclared.removeAll(declared);
        return undeclared.isEmpty()
                ? Verdict.PASS
                : deny("arguments " + names(undeclared) + " are not declared by " + tool + "'s definition");
    }

    /** The keys of the schema's {@code properties}; a schema without them declares none. */
    private static Set<String> declaredNames(ObjectValue schema) {
        return schema.field("properties")
                .filter(ObjectValue.class::isInstance)
                .map(properties -> ((ObjectValue) properties).fields().keySet())
                .orElse(Set.of());
    }

    private static String names(Set<String> names) {
        String quoted = names.stream().limit(MAX_NAMES_IN_REASON).map(name -> "'" + name + "'")
                .collect(Collectors.joining(", "));
        return names.size() <= MAX_NAMES_IN_REASON ? quoted : quoted + " and " + (names.size() - MAX_NAMES_IN_REASON) + " more";
    }

    private static Verdict deny(String reason) {
        return new Verdict.Deny(reason, Origin.ACCESS);
    }
}
