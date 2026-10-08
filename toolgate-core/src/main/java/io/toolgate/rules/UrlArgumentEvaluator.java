package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.TextValue;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import io.toolgate.validate.UrlCheck;
import java.util.Objects;
import java.util.Optional;

/** Applies one agent's URL rules to one argument of one tool; passes every other call. */
final class UrlArgumentEvaluator implements Evaluator {

    private final String agentId;
    private final ToolArgument argument;
    private final UrlCheck.Rule rule;

    UrlArgumentEvaluator(String agentId, ToolArgument argument, UrlCheck.Rule rule) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.argument = Objects.requireNonNull(argument, "argument");
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    @Override
    public String id() {
        return argument.ruleId();
    }

    @Override
    public Stage stage() {
        return Stage.ARGUMENTS;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        if (!context.agent().id().equals(agentId) || !context.call().tool().equals(argument.tool())) {
            return Verdict.PASS;
        }
        Optional<ArgValue> value = context.call().args().field(argument.name());
        if (value.isEmpty()) {
            return Verdict.PASS;
        }
        if (!(value.get() instanceof TextValue(String url))) {
            return deny("must be a string");
        }
        return switch (UrlCheck.check(url, rule)) {
            case UrlCheck.Accepted accepted -> Verdict.PASS;
            case UrlCheck.Rejected(String reason) -> deny(reason);
        };
    }

    private Verdict deny(String reason) {
        return new Verdict.Deny("argument '" + argument.name() + "': " + reason, Origin.ACCESS);
    }
}
