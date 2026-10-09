package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a proposed policy would have decided differently on recorded history.
 *
 * @param sessions       sessions replayed
 * @param calls          tool calls replayed
 * @param agents         the agents that made them
 * @param changes        every call whose decision would change, in history's order
 * @param assumedResults calls that run only under the proposed policy, so no result was recorded; each
 *                       is taken to succeed, with its tool's labels
 */
public record ReplayReport(int sessions, int calls, Set<String> agents, List<Change> changes, int assumedResults) {

    /** Copies the collections. */
    public ReplayReport {
        agents = Set.copyOf(agents);
        changes = List.copyOf(changes);
    }

    /**
     * One call whose decision would change.
     *
     * @param sessionId       the session
     * @param callId          the call
     * @param agentId         the agent that made it
     * @param tool            the tool it called
     * @param before          what the recorded policy computed
     * @param after           what the proposed policy computes
     * @param afterDivergence whether an earlier call of the session already changed; the model made
     *                        this call after seeing the old outcome, and might not have made it at all
     */
    public record Change(String sessionId, String callId, String agentId, ToolId tool, Decision before, Decision after,
                         boolean afterDivergence) {
        /** Requires every component. */
        public Change {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(callId, "callId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }

    /**
     * Calls whose decision would not change.
     *
     * @return the count
     */
    public int unchanged() {
        return calls - changes.size();
    }

    /**
     * The report as text, grouped by tool and by the change.
     *
     * <pre>
     * Replayed 48,212 tool calls in 3,104 sessions (agents: invoice-processor, support-chat)
     * Decisions that would change: 1,152
     *    1,152  local/sendEmail  allow -&gt; deny  rule sendEmail.to
     * Unchanged: 47,060
     * </pre>
     *
     * @return the text, one line per group
     */
    public String summary() {
        var text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Replayed %,d tool calls in %,d sessions (agents: %s)%n", calls, sessions,
                String.join(", ", agents.stream().sorted().toList())));
        text.append(String.format(Locale.ROOT, "Decisions that would change: %,d%n", changes.size()));
        Map<String, Long> groups = changes.stream().collect(Collectors.groupingBy(ReplayReport::group, TreeMap::new,
                Collectors.counting()));
        groups.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(group -> text.append(String.format(Locale.ROOT, "  %,7d  %s%n", group.getValue(), group.getKey())));
        long diverged = changes.stream().filter(Change::afterDivergence).count();
        if (diverged > 0) {
            text.append(String.format(Locale.ROOT,
                    "  of which %,d come after an earlier change in their session, which the model might not have made%n",
                    diverged));
        }
        text.append(String.format(Locale.ROOT, "Unchanged: %,d%n", unchanged()));
        if (assumedResults > 0) {
            text.append(String.format(Locale.ROOT,
                    "Assumed: %,d calls run only under the proposed policy; each is taken to succeed%n", assumedResults));
        }
        text.append("Not replayed: result rules, because tool outputs are not stored\n");
        return text.toString();
    }

    private static String group(Change change) {
        Function<Decision, String> kind = decision -> switch (decision) {
            case Decision.Allow allow -> "allow";
            case Decision.Deny deny -> "deny";
            case Decision.Defer defer -> "defer";
            case Decision.Downgrade downgrade -> "downgrade to " + downgrade.safeTool();
            case Decision.Terminate terminate -> "terminate";
        };
        String rule = change.after() instanceof Decision.Allow ? change.before().ruleId() : change.after().ruleId();
        return change.tool() + "  " + kind.apply(change.before()) + " -> " + kind.apply(change.after()) + "  rule " + rule;
    }
}
