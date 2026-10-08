package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.TextValue;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.OnViolation;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import io.toolgate.support.Decimals;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies one agent's numeric limits to one argument. Deny limits are checked first, so a value
 * past both limits is denied, not reviewed.
 */
final class NumericEvaluator implements Evaluator {

    private final String agentId;
    private final ToolArgument argument;
    private final List<ArgumentRule> rules;

    NumericEvaluator(String agentId, ToolArgument argument, List<ArgumentRule> rules) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.argument = Objects.requireNonNull(argument, "argument");
        this.rules = List.copyOf(rules);
    }

    /**
     * Rejects limits that contradict each other or can never fire, when the policy is built.
     *
     * @param rules one argument's numeric rules
     * @throws IllegalArgumentException if a defer limit is past its deny limit, or atLeast is above atMost
     */
    static void requireReachableLimits(List<ArgumentRule> rules) {
        Optional<BigDecimal> maxDeny = limit(rules, true, OnViolation.DENY);
        Optional<BigDecimal> maxDefer = limit(rules, true, OnViolation.DEFER);
        Optional<BigDecimal> minDeny = limit(rules, false, OnViolation.DENY);
        Optional<BigDecimal> minDefer = limit(rules, false, OnViolation.DEFER);
        if (maxDeny.isPresent() && maxDefer.isPresent() && maxDefer.get().compareTo(maxDeny.get()) >= 0) {
            throw new IllegalArgumentException("atMost(" + maxDefer.get() + ").otherwise(DEFER) can never fire above atMost("
                    + maxDeny.get() + "); the review limit must be lower");
        }
        if (minDeny.isPresent() && minDefer.isPresent() && minDefer.get().compareTo(minDeny.get()) <= 0) {
            throw new IllegalArgumentException("atLeast(" + minDefer.get() + ").otherwise(DEFER) can never fire below atLeast("
                    + minDeny.get() + "); the review limit must be higher");
        }
        if (minDeny.isPresent() && maxDeny.isPresent() && minDeny.get().compareTo(maxDeny.get()) > 0) {
            throw new IllegalArgumentException("atLeast(" + minDeny.get() + ") is above atMost(" + maxDeny.get()
                    + "), so every value is denied");
        }
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
        Optional<BigDecimal> number = number(value.get());
        if (number.isEmpty()) {
            return new Verdict.Deny(reason("must be a number, or plain decimal text"), Origin.ACCESS);
        }
        for (OnViolation action : List.of(OnViolation.DENY, OnViolation.DEFER)) {
            Optional<String> broken = brokenLimit(number.get(), action);
            if (broken.isPresent()) {
                return switch (action) {
                    case DENY -> new Verdict.Deny(reason(broken.get()), Origin.ACCESS);
                    case DEFER -> new Verdict.Defer(reason(broken.get()), Origin.ACCESS);
                };
            }
        }
        return Verdict.PASS;
    }

    private Optional<String> brokenLimit(BigDecimal number, OnViolation action) {
        Optional<BigDecimal> max = limit(rules, true, action);
        if (max.isPresent() && number.compareTo(max.get()) > 0) {
            return Optional.of(Decimals.describe(number) + " is above " + Decimals.describe(max.get()));
        }
        Optional<BigDecimal> min = limit(rules, false, action);
        if (min.isPresent() && number.compareTo(min.get()) < 0) {
            return Optional.of(Decimals.describe(number) + " is below " + Decimals.describe(min.get()));
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> limit(List<ArgumentRule> rules, boolean upper, OnViolation action) {
        return rules.stream()
                .<Optional<BigDecimal>>map(rule -> switch (rule) {
                    case ArgumentRule.AtMost(BigDecimal limit, OnViolation onViolation) ->
                            upper && onViolation == action ? Optional.of(limit) : Optional.empty();
                    case ArgumentRule.AtLeast(BigDecimal limit, OnViolation onViolation) ->
                            !upper && onViolation == action ? Optional.of(limit) : Optional.empty();
                    default -> Optional.empty();
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<BigDecimal> number(ArgValue value) {
        return switch (value) {
            case NumberValue(BigDecimal number) -> Optional.of(number);
            case TextValue(String text) -> Decimals.parsePlain(text);
            default -> Optional.empty();
        };
    }

    private String reason(String problem) {
        return "argument '" + argument.name() + "': " + problem;
    }
}
