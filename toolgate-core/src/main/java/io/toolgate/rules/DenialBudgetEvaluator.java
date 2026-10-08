package io.toolgate.rules;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;

/**
 * Ends the run on the first call after the session has collected its denial budget, so a model,
 * or an attacker steering it, cannot keep probing for a way through.
 */
final class DenialBudgetEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.DENIAL_BUDGET;
    }

    @Override
    public Stage stage() {
        return Stage.LIMITS;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        int limit = context.policy().denialBudget();
        return context.session().denials() < limit
                ? Verdict.PASS
                : new Verdict.Terminate("denial budget of " + limit + " reached");
    }
}
