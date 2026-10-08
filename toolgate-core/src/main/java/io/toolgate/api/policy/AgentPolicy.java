package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
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
 */
public record AgentPolicy(String id, Set<ToolId> allowedTools, Budget budget,
                          Map<ToolArgument, List<ArgumentRule>> argumentRules) {

    /** Validates and copies every component. */
    public AgentPolicy {
        requireNonBlank(id, "agent id");
        allowedTools = Set.copyOf(allowedTools);
        Objects.requireNonNull(budget, "budget");
        argumentRules = argumentRules.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
