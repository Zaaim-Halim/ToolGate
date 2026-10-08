package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The validated rules for one agent.
 *
 * @param id            the agent's ID, as tool calls name it
 * @param allowedTools  the only tools this agent may call
 * @param budget        limits on each of its sessions
 * @param argumentRules rules on argument values, by argument; every tool here is also allowed
 * @param interactive   whether a person can be present to confirm deferred calls
 * @param downgrades    safe tools to run instead, by tool, when a taint rule objects; all allowed
 * @param onTrifecta    what the trifecta rule does when no downgrade applies
 * @param taintRules    the agent's own taint rules
 * @param requiredRoles roles a principal needs to call a tool, any one of which is enough, by tool
 * @param resultRules   checks on what tools return, before the model reads it
 * @param approvalExpiry how long a deferred call waits for a decision before it expires
 */
public record AgentPolicy(String id, Set<ToolId> allowedTools, Budget budget,
                          Map<ToolArgument, List<ArgumentRule>> argumentRules,
                          boolean interactive, Map<ToolId, ToolId> downgrades, TaintAction onTrifecta,
                          List<TaintRule> taintRules, Map<ToolId, Set<String>> requiredRoles,
                          List<ResultRule> resultRules, Duration approvalExpiry) {

    /** Validates and copies every component. */
    public AgentPolicy {
        requireNonBlank(id, "agent id");
        allowedTools = Set.copyOf(allowedTools);
        Objects.requireNonNull(budget, "budget");
        argumentRules = argumentRules.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        downgrades = Collections.unmodifiableMap(new LinkedHashMap<>(downgrades));
        Objects.requireNonNull(onTrifecta, "onTrifecta");
        taintRules = List.copyOf(taintRules);
        requiredRoles = requiredRoles.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        resultRules = List.copyOf(resultRules);
        Objects.requireNonNull(approvalExpiry, "approvalExpiry");
        if (approvalExpiry.isNegative() || approvalExpiry.isZero()) {
            throw new IllegalArgumentException("approvalExpiry must be positive");
        }
    }
}
