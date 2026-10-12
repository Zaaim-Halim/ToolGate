package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.policy.Binding;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.Objects;
import java.util.Optional;

/** One agent's binding: the bound argument must take a value the session trusts; passes every other call. */
final class BindingEvaluator implements Evaluator {

    private final String agentId;
    private final Binding binding;

    BindingEvaluator(String agentId, Binding binding) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public String id() {
        return binding.ruleId();
    }

    @Override
    public Stage stage() {
        return Stage.ARGUMENTS;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        if (!context.agent().id().equals(agentId) || !context.call().tool().equals(binding.argument().tool())) {
            return Verdict.PASS;
        }
        Optional<ArgValue> value = context.call().args().field(binding.argument().name());
        if (value.isEmpty()) {
            return Verdict.PASS;
        }
        if (BoundValues.bound(binding, value.get(), fingerprint -> context.session().trusts(binding.sourceKey(), fingerprint))) {
            return Verdict.PASS;
        }
        return new Verdict.Deny("argument '" + binding.argument().name() + "' is not a value " + binding.source() + " field '"
                + binding.field() + "' returned before the session read untrusted content", Origin.ACCESS);
    }
}
