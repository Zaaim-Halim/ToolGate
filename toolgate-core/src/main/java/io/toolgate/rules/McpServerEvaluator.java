package io.toolgate.rules;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;

/**
 * Denies every tool from an MCP server the policy does not list. The policy builder already
 * rejects allow-lists naming such tools; this catches servers connected after the policy was
 * written, and names the cause in the audit trail.
 */
final class McpServerEvaluator implements Evaluator {

    @Override
    public String id() {
        return BuiltInRules.MCP_SERVERS;
    }

    @Override
    public Stage stage() {
        return Stage.IDENTIFY;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        return context.call().tool().mcpServer()
                .filter(server -> !context.policy().mcpServers().contains(server))
                .<Verdict>map(server -> new Verdict.Deny("MCP server '" + server + "' is not allowed", Origin.ACCESS))
                .orElse(Verdict.PASS);
    }
}
