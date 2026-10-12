package io.toolgate.api.policy;

import io.toolgate.api.Effect;
import io.toolgate.api.Label;
import java.util.Objects;
import java.util.Set;

/**
 * What the policy says a tool does with data.
 *
 * @param labels  the tool's data labels
 * @param effect  whether its action can be undone
 * @param trusted paths to fields of its output that the application's own system sets, not the text
 *                someone else wrote, such as a ticket's {@code customer}; arguments may be bound to them
 */
public record ToolPolicy(Set<Label> labels, Effect effect, Set<String> trusted) {

    /** The default for a local tool the policy does not describe: no labels, irreversible. */
    public static final ToolPolicy LOCAL_DEFAULT = new ToolPolicy(Set.of(), Effect.IRREVERSIBLE, Set.of());

    /**
     * The default for an MCP tool the policy does not describe: its output comes from a third party,
     * so it is untrusted content, and its effect is irreversible.
     */
    public static final ToolPolicy MCP_DEFAULT =
            new ToolPolicy(Set.of(Label.UNTRUSTED_CONTENT), Effect.IRREVERSIBLE, Set.of());

    /** Copies the labels and trusted fields. */
    public ToolPolicy {
        labels = Set.copyOf(labels);
        Objects.requireNonNull(effect, "effect");
        trusted = Set.copyOf(trusted);
    }

    /**
     * A tool with no trusted fields.
     *
     * @param labels the tool's data labels
     * @param effect whether its action can be undone
     */
    public ToolPolicy(Set<Label> labels, Effect effect) {
        this(labels, effect, Set.of());
    }

    /**
     * Whether the tool has a label.
     *
     * @param label the label
     * @return {@code true} if the tool carries it
     */
    public boolean has(Label label) {
        return labels.contains(label);
    }
}
