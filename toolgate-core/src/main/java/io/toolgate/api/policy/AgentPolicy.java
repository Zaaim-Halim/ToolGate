package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;
import java.util.Set;

/**
 * The validated rules for one agent.
 *
 * @param id           the agent's ID, as tool calls name it
 * @param allowedTools the only tools this agent may call
 * @param budget       limits on each of its sessions
 */
public record AgentPolicy(String id, Set<ToolId> allowedTools, Budget budget) {

    /** Validates and copies every component. */
    public AgentPolicy {
        requireNonBlank(id, "agent id");
        allowedTools = Set.copyOf(allowedTools);
        Objects.requireNonNull(budget, "budget");
    }
}
