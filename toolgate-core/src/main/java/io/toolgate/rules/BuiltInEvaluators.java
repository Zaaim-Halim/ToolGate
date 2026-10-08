package io.toolgate.rules;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.Evaluator;
import io.toolgate.spi.HostResolver;
import io.toolgate.validate.ResolvedAddresses;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** The evaluators every gate runs. */
public final class BuiltInEvaluators {

    private BuiltInEvaluators() {}

    /**
     * Every built-in evaluator, plus one for each argument rule in the policy, without resolving names.
     *
     * @param policy the policy whose argument rules to apply
     * @return the evaluators, built-ins first
     */
    public static List<Evaluator> forPolicy(ToolGatePolicy policy) {
        return withNameCheck(policy, host -> Optional.empty());
    }

    /**
     * Every built-in evaluator, plus one for each argument rule in the policy, resolving URL host
     * names with {@code resolver} and denying names that point at special-purpose addresses.
     *
     * @param policy   the policy whose argument rules to apply
     * @param resolver the application's resolver
     * @return the evaluators, built-ins first
     */
    public static List<Evaluator> forPolicy(ToolGatePolicy policy, HostResolver resolver) {
        return withNameCheck(policy, host -> ResolvedAddresses.problem(resolver, host));
    }

    private static List<Evaluator> withNameCheck(ToolGatePolicy policy, Function<String, Optional<String>> nameCheck) {
        var evaluators = new ArrayList<>(all());
        policy.agents().values().forEach(agent -> agent.argumentRules().forEach((argument, rules) ->
                evaluators.add(new ArgumentEvaluator(agent.id(), argument, ArgumentChecks.compile(rules, nameCheck)))));
        return List.copyOf(evaluators);
    }

    /** Every built-in evaluator, in order within each stage. */
    public static List<Evaluator> all() {
        return List.of(
                new DenialBudgetEvaluator(),
                new ToolCallBudgetEvaluator(),
                new McpServerEvaluator(),
                new AllowListEvaluator());
    }
}
