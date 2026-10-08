package io.toolgate.rules;

import io.toolgate.api.Label;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import io.toolgate.spi.SessionState;
import java.util.List;

/**
 * The lethal trifecta: a session holding private data and untrusted content must not reach an
 * external sink, because the untrusted content may be steering what the sink carries out.
 */
final class TrifectaEvaluator implements Evaluator {

    private static final List<Label> SOURCES = List.of(Label.PRIVATE_DATA, Label.UNTRUSTED_CONTENT);

    @Override
    public String id() {
        return BuiltInRules.TAINT_TRIFECTA;
    }

    @Override
    public Stage stage() {
        return Stage.SESSION;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        SessionState session = context.session();
        ToolId tool = context.call().tool();
        if (!session.holds(Label.PRIVATE_DATA) || !session.holds(Label.UNTRUSTED_CONTENT)
                || !context.policy().tool(tool).has(Label.EXTERNAL_SINK)) {
            return Verdict.PASS;
        }
        return TaintVerdicts.object(context.agent(), tool, context.agent().onTrifecta(),
                "session holds " + TaintVerdicts.provenance(session, SOURCES) + ", and " + tool + " is an external sink");
    }
}
