package io.toolgate.rules;

import io.toolgate.api.Label;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.TaintAction;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Verdict;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.TaintSource;
import java.util.Collection;
import java.util.stream.Collectors;

/** What every taint rule does once it objects: the agent's downgrade if it has one, else its action. */
final class TaintVerdicts {

    private TaintVerdicts() {}

    static Verdict object(AgentPolicy agent, ToolId tool, TaintAction action, String reason) {
        ToolId safeTool = agent.downgrades().get(tool);
        if (safeTool != null) {
            return new Verdict.Downgrade(safeTool);
        }
        return switch (action) {
            case BLOCK -> new Verdict.Deny(reason, Origin.TAINT);
            case DEFER -> new Verdict.Defer(reason, Origin.TAINT);
        };
    }

    /** Names where each label came from, such as "UNTRUSTED_CONTENT (from call job/2, local/fetchSupplierPage)". */
    static String provenance(SessionState session, Collection<Label> labels) {
        return labels.stream()
                .sorted()
                .map(label -> {
                    TaintSource source = session.taint().get(label);
                    return label + " (from call " + source.callId() + ", " + source.tool() + ")";
                })
                .collect(Collectors.joining(" and "));
    }
}
