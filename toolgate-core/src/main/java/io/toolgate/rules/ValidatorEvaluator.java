package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Applies one agent's validator rules to one argument; a validator that throws denies. */
final class ValidatorEvaluator implements Evaluator {

    private final String agentId;
    private final ToolArgument argument;
    private final List<ArgumentRule.Validated> rules;

    ValidatorEvaluator(String agentId, ToolArgument argument, List<ArgumentRule> rules) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.argument = Objects.requireNonNull(argument, "argument");
        this.rules = rules.stream().map(ArgumentRule.Validated.class::cast).toList();
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
        for (ArgumentRule.Validated rule : rules) {
            Optional<String> problem = problem(rule, value.get());
            if (problem.isPresent()) {
                return new Verdict.Deny("argument '" + argument.name() + "': " + problem.get(), Origin.ACCESS);
            }
        }
        return Verdict.PASS;
    }

    private static Optional<String> problem(ArgumentRule.Validated rule, ArgValue value) {
        try {
            return Objects.requireNonNull(rule.validator().problem(value), "a validator returned null");
        } catch (RuntimeException failed) {
            // Fail closed, and keep the model's value out of the reason: a validator's message may quote it.
            return Optional.of(rule.kind() + " could not check the value (" + failed.getClass().getSimpleName() + ")");
        }
    }
}
