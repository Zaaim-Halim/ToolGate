package io.toolgate.rules;

import io.toolgate.engine.Evaluator;
import java.util.List;

/** The evaluators every gate runs. */
public final class BuiltInEvaluators {

    private BuiltInEvaluators() {}

    /** Every built-in evaluator, in order within each stage. */
    public static List<Evaluator> all() {
        return List.of(
                new DenialBudgetEvaluator(),
                new ToolCallBudgetEvaluator(),
                new McpServerEvaluator(),
                new AllowListEvaluator());
    }
}
