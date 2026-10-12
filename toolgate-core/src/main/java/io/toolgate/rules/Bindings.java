package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.policy.Binding;
import io.toolgate.engine.EvaluationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a call goes where the application's own data says: the agent binds arguments of the call's
 * tool, at least one of them is present, and every one present takes only trusted values. An absent
 * bound argument, such as an optional {@code cc}, sends nowhere. The trifecta and irreversible rules let
 * such a call through.
 */
public final class Bindings {

    private Bindings() {}

    /**
     * The bindings that hold for a call, if all of its tool's bindings hold.
     *
     * @param context the call and its session
     * @return the call's bindings with a value, or empty if none has one or any of them fails
     */
    public static List<Binding> holding(EvaluationContext context) {
        List<Binding> bindings = context.agent().bindings().values().stream()
                .filter(binding -> binding.argument().tool().equals(context.call().tool()))
                .toList();
        if (bindings.isEmpty()) {
            return List.of();
        }
        var present = new ArrayList<Binding>();
        for (Binding binding : bindings) {
            Optional<ArgValue> value = context.call().args().field(binding.argument().name());
            if (value.isEmpty()) {
                continue;
            }
            if (!BoundValues.bound(binding, value.get(),
                    fingerprint -> context.session().trusts(binding.sourceKey(), fingerprint))) {
                return List.of();
            }
            present.add(binding);
        }
        return List.copyOf(present);
    }
}
