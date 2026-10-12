package io.toolgate.rules;

import io.toolgate.api.Label;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.TaintAction;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.List;

/**
 * Once a session has read untrusted content, an irreversible action could be the attacker's: it is
 * downgraded if the agent has a safe tool for it, and otherwise held for review, never blocked,
 * since it may well be legitimate. A tool without a stated effect counts as irreversible.
 */
final class IrreversibleEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.TAINT_IRREVERSIBLE;
    }

    @Override
    public Stage stage() {
        return Stage.SESSION;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        ToolId tool = context.call().tool();
        if (!TaintConditions.irreversible(context) || !Bindings.holding(context).isEmpty()) {
            return Verdict.PASS;
        }
        return TaintVerdicts.object(context.agent(), tool, TaintAction.DEFER,
                "session holds " + TaintVerdicts.provenance(context.session(), List.of(Label.UNTRUSTED_CONTENT))
                        + ", and " + tool + " is irreversible");
    }
}
