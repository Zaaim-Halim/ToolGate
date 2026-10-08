package io.toolgate.api.policy;

import io.toolgate.api.Label;
import io.toolgate.api.ToolId;
import java.util.Objects;
import java.util.Set;

/**
 * An agent's own taint rule: once the session holds every one of {@code labels}, calls to
 * {@code tool} get {@code action}, unless the agent downgrades the tool.
 *
 * @param tool   the tool
 * @param labels source labels the session must all hold
 * @param action what happens to the call
 */
public record TaintRule(ToolId tool, Set<Label> labels, TaintAction action) {

    /** Requires a tool, at least one source label and an action. */
    public TaintRule {
        Objects.requireNonNull(tool, "tool");
        labels = Set.copyOf(labels);
        Objects.requireNonNull(action, "action");
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("a taint rule needs at least one label");
        }
        if (labels.contains(Label.EXTERNAL_SINK)) {
            throw new IllegalArgumentException("EXTERNAL_SINK marks tools, not sessions; use PRIVATE_DATA or UNTRUSTED_CONTENT");
        }
    }

    /**
     * The rule's ID in decisions and audit events.
     *
     * @return such as {@code sendEmail.whenTainted}
     */
    public String ruleId() {
        return (tool.isLocal() ? tool.name() : tool.toString()) + ".whenTainted";
    }
}
