package io.toolgate.rules;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.Evaluator;
import java.util.ArrayList;
import java.util.List;

/** The evaluators every gate runs. */
public final class BuiltInEvaluators {

    private BuiltInEvaluators() {}

    /**
     * Every built-in evaluator, plus one for each argument rule in the policy.
     *
     * @param policy the policy whose argument rules to apply
     * @return the evaluators, built-ins first
     */
    public static List<Evaluator> forPolicy(ToolGatePolicy policy) {
        var evaluators = new ArrayList<>(all());
        policy.agents().values().forEach(agent -> agent.argumentRules().forEach((argument, rules) ->
                evaluators.add(new UrlArgumentEvaluator(agent.id(), argument, UrlArgumentRules.compile(rules)))));
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
