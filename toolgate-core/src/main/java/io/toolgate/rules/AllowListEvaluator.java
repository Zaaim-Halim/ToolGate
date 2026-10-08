package io.toolgate.rules;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;

/**
 * Denies any tool not on the agent's allow-list. Tools are compared with their source, so a
 * remote tool never matches a local tool of the same name.
 */
final class AllowListEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.ALLOW_LIST;
    }

    @Override
    public Stage stage() {
        return Stage.IDENTIFY;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        var tool = context.call().tool();
        return context.agent().allowedTools().contains(tool)
                ? Verdict.PASS
                : new Verdict.Deny(tool + " is not allowed for agent '" + context.agent().id() + "'", Origin.ACCESS);
    }
}
