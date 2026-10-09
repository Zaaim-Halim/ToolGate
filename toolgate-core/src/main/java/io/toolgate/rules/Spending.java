package io.toolgate.rules;

import io.toolgate.api.ToolCall;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.support.Decimals;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** What a call that runs adds to the session's totals: its value for every argument with a total limit. */
public final class Spending {

    private Spending() {}

    /**
     * The amounts a call adds, by rule ID.
     *
     * @param policy the policy
     * @param call   a call that runs, or is released
     * @return its amounts for arguments with a total limit; only non-negative numbers with a reasonable
     *         plain text count, so a monitored call can never put a billion-digit value into a total
     */
    public static Map<String, BigDecimal> of(ToolGatePolicy policy, ToolCall call) {
        Optional<AgentPolicy> agent = policy.agent(call.agentId());
        if (agent.isEmpty()) {
            return Map.of();
        }
        var spent = new TreeMap<String, BigDecimal>();
        agent.get().argumentRules().forEach((argument, rules) -> {
            if (!argument.tool().equals(call.tool()) || !NumericEvaluator.hasTotal(List.copyOf(rules))) {
                return;
            }
            call.args().field(argument.name())
                    .flatMap(NumericEvaluator::numberOf)
                    .filter(number -> number.signum() >= 0 && Decimals.plainText(number).isPresent())
                    .ifPresent(number -> spent.put(argument.ruleId(), number));
        });
        return spent;
    }
}
