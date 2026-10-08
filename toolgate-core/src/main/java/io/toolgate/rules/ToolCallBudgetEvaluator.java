package io.toolgate.rules;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;

/** Ends the run once the session has made as many tool calls as its agent's budget allows. */
final class ToolCallBudgetEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.TOOL_CALL_BUDGET;
    }

    @Override
    public Stage stage() {
        return Stage.LIMITS;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        int limit = context.agent().budget().toolCalls();
        return context.session().toolCalls() < limit
                ? Verdict.PASS
                : new Verdict.Terminate("tool-call budget of " + limit + " reached");
    }
}
