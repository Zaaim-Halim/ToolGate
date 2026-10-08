package io.toolgate.policy;

import io.toolgate.api.Mode;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ToolArgument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Computes a {@code ToolGatePolicy}'s hash from a canonical text form, so every audit event names the exact policy
 * that decided it, and two JVMs running the same policy agree on the hash.
 *
 * <p>The form never relies on {@code hashCode} or iteration order: collections whose order has no
 * meaning are sorted, and every string is length-prefixed so no value can imitate a separator.
 */
public final class PolicyHash {

    private static final String FORMAT_VERSION = "toolgate-policy 1";
    private static final String PREFIX = "sha256:";

    private PolicyHash() {}

    /**
     * The hash of a policy.
     *
     * @param mode         the policy-wide mode
     * @param ruleModes    per-rule modes
     * @param denialBudget the denial budget
     * @param mcpServers   allowed MCP servers
     * @param agents       agents by ID
     * @return {@code sha256:} and 64 hex digits
     */
    public static String of(Mode mode, Map<String, Mode> ruleModes, int denialBudget,
                            Collection<String> mcpServers, Map<String, AgentPolicy> agents) {
        var text = new StringBuilder(FORMAT_VERSION).append('\n');
        line(text, "mode", mode.toString());
        ruleModes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line(text, "ruleMode", entry.getKey(), entry.getValue().toString()));
        line(text, "denialBudget", Integer.toString(denialBudget));
        mcpServers.stream().sorted().forEach(server -> line(text, "mcpServer", server));
        agents.values().stream().sorted(Comparator.comparing(AgentPolicy::id)).forEach(agent -> agent(text, agent));
        return PREFIX + HexFormat.of().formatHex(sha256(text.toString()));
    }

    private static void agent(StringBuilder text, AgentPolicy agent) {
        line(text, "agent", agent.id());
        agent.allowedTools().stream().map(ToolId::toString).sorted().forEach(tool -> line(text, " allow", tool));
        line(text, " budget.toolCalls", Integer.toString(agent.budget().toolCalls()));
        agent.argumentRules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolArgument::ruleId)))
                .forEach(entry -> {
                    line(text, " rule", entry.getKey().ruleId());
                    entry.getValue().stream().map(PolicyHash::rule).sorted().forEach(rule -> text.append("  ").append(rule));
                });
    }

    private static String rule(ArgumentRule rule) {
        var text = new StringBuilder();
        switch (rule) {
            case ArgumentRule.Hosts(List<String> patterns) -> line(text, "hosts", sorted(patterns));
            case ArgumentRule.Schemes schemes -> line(text, "schemes", sorted(schemes.schemes()));
            case ArgumentRule.Ports ports -> line(text, "ports",
                    ports.ports().stream().sorted().map(String::valueOf).toArray(String[]::new));
            case ArgumentRule.Under(List<String> roots) -> line(text, "under", sorted(roots));
            case ArgumentRule.NotUnder(List<String> roots) -> line(text, "notUnder", sorted(roots));
        }
        return text.toString();
    }

    private static String[] sorted(Collection<String> values) {
        return values.stream().sorted().toArray(String[]::new);
    }

    private static void line(StringBuilder text, String key, String... values) {
        text.append(key);
        for (String value : values) {
            text.append(' ').append(value.length()).append(':').append(value);
        }
        text.append('\n');
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }

}
