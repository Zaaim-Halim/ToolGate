package io.toolgate.rules;

import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.Optional;

/**
 * Denies a pinned tool whose definition is no longer the one reviewed, such as after an MCP server's
 * update, or is not known at all. The reason names both pins and quotes the start of the new
 * definition; the policy holds only a hash, so what exactly changed is found by comparing with the
 * definition that was reviewed.
 */
final class PinEvaluator implements Evaluator {

    /** Enough to see a changed description or the first properties, without filling the audit log. */
    private static final int MAX_DEFINITION_IN_REASON = 1_000;


    @Override
    public String id() {
        return BuiltInRules.TOOL_PINNED;
    }

    @Override
    public Stage stage() {
        return Stage.IDENTIFY;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        ToolId tool = context.call().tool();
        Optional<String> pin = context.policy().pin(tool);
        if (pin.isEmpty()) {
            return Verdict.PASS;
        }
        Optional<ToolDefinition> current = context.definitions().definition(tool);
        if (current.isEmpty()) {
            return new Verdict.Deny(tool + " is pinned, but the tool catalog has no definition for it", Origin.ACCESS);
        }
        String now = current.get().sha256();
        if (now.equals(pin.get())) {
            return Verdict.PASS;
        }
        return new Verdict.Deny(tool + "'s definition changed since it was pinned: pinned " + pin.get() + ", now "
                + now + "; now defined as " + abbreviated(current.get().canonicalJson()), Origin.ACCESS);
    }

    private static String abbreviated(String definition) {
        return definition.length() <= MAX_DEFINITION_IN_REASON
                ? definition
                : definition.substring(0, MAX_DEFINITION_IN_REASON) + "…";
    }
}
