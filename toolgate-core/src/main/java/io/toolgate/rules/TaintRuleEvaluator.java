package io.toolgate.rules;

import io.toolgate.api.policy.TaintRule;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.Objects;

/** One agent's own whenTainted rule; passes every other agent and tool. */
final class TaintRuleEvaluator implements Evaluator {

    private final String agentId;
    private final TaintRule rule;

    TaintRuleEvaluator(String agentId, TaintRule rule) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    @Override
    public String id() {
        return rule.ruleId();
    }

    @Override
    public Stage stage() {
        return Stage.SESSION;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        if (!context.agent().id().equals(agentId) || !context.call().tool().equals(rule.tool())
                || !rule.labels().stream().allMatch(context.session()::holds)) {
            return Verdict.PASS;
        }
        return TaintVerdicts.object(context.agent(), rule.tool(), rule.action(),
                "session holds " + TaintVerdicts.provenance(context.session(), rule.labels()));
    }
}
