package io.toolgate.rules;

import io.toolgate.api.ToolId;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Lets one agent call one tool only for principals holding one of the required roles. */
final class RoleEvaluator implements Evaluator {

    private final String agentId;
    private final ToolId tool;
    private final Set<String> roles;

    RoleEvaluator(String agentId, ToolId tool, Set<String> roles) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.roles = Set.copyOf(roles);
    }

    @Override
    public String id() {
        return (tool.isLocal() ? tool.name() : tool.toString()) + ".roles";
    }

    @Override
    public Stage stage() {
        return Stage.IDENTIFY;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        if (!context.agent().id().equals(agentId) || !context.call().tool().equals(tool)
                || !Collections.disjoint(roles, context.call().principal().roles())) {
            return Verdict.PASS;
        }
        return new Verdict.Deny("principal " + context.call().principal().id() + " has none of the roles "
                + new TreeSet<>(roles) + " that " + tool + " requires", Origin.ACCESS);
    }
}
