package io.toolgate.rules;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;

/**
 * Denies a call to a tool while an approved call of the same tool in the session has an unknown
 * outcome: if nobody knows whether the first refund went out, a second must not follow. Runs in the
 * session stage, so it also refuses the release of another approved call of that tool.
 */
final class UnresolvedEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.APPROVAL_UNRESOLVED;
    }

    @Override
    public Stage stage() {
        return Stage.SESSION;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        return context.session().unresolvedCallOf(context.call().tool())
                .<Verdict>map(approvalId -> new Verdict.Deny("approved call " + approvalId + " of "
                        + context.call().tool() + " has an unknown outcome; resolve it first", Origin.ACCESS))
                .orElse(Verdict.PASS);
    }
}
