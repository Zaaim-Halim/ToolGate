package io.toolgate.policy;

import io.toolgate.api.Mode;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.Binding;
import io.toolgate.api.policy.ResultCheck;
import io.toolgate.api.policy.ResultRule;
import io.toolgate.api.policy.TaintRule;
import io.toolgate.api.policy.ToolPolicy;
import io.toolgate.api.policy.ToolArgument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
     * @param tools        tool descriptions
     * @param pins         pinned tool definitions; absent from the text when empty, so policies
     *                     without pins keep the hash they had before pins existed
     * @param agents       agents by ID
     * @param superAdmins  super admin roles; absent from the text when empty, as pins are
     * @param auditors     auditor roles; absent from the text when empty
     * @param superAdminConfirmation whether a super admin's approval needs a second person; absent when not
     * @return {@code sha256:} and 64 hex digits
     */
    public static String of(Mode mode, Map<String, Mode> ruleModes, int denialBudget,
                            Collection<String> mcpServers, Map<ToolId, ToolPolicy> tools,
                            Map<ToolId, String> pins, Map<String, AgentPolicy> agents,
                            Collection<String> superAdmins, Collection<String> auditors,
                            boolean superAdminConfirmation) {
        var text = new StringBuilder(FORMAT_VERSION).append('\n');
        line(text, "mode", mode.toString());
        ruleModes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line(text, "ruleMode", entry.getKey(), entry.getValue().toString()));
        line(text, "denialBudget", Integer.toString(denialBudget));
        mcpServers.stream().sorted().forEach(server -> line(text, "mcpServer", server));
        tools.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolId::toString)))
                .forEach(entry -> {
                    line(text, "tool", withLabels(entry.getValue().labels(),
                            entry.getKey().toString(), entry.getValue().effect().name()));
                    // Only when declared, so policies without trusted fields keep their hash.
                    if (!entry.getValue().trusted().isEmpty()) {
                        line(text, " trusted", entry.getValue().trusted().stream().sorted().toArray(String[]::new));
                    }
                });
        pins.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolId::toString)))
                .forEach(entry -> line(text, "pin", entry.getKey().toString(), entry.getValue()));
        agents.values().stream().sorted(Comparator.comparing(AgentPolicy::id)).forEach(agent -> agent(text, agent));
        if (!superAdmins.isEmpty()) {
            line(text, "superAdmins", superAdmins.stream().sorted().toArray(String[]::new));
        }
        if (!auditors.isEmpty()) {
            line(text, "auditors", auditors.stream().sorted().toArray(String[]::new));
        }
        if (superAdminConfirmation) {
            line(text, "superAdminConfirmation", "true");
        }
        return PREFIX + HexFormat.of().formatHex(sha256(text.toString()));
    }

    private static void agent(StringBuilder text, AgentPolicy agent) {
        line(text, "agent", agent.id());
        agent.allowedTools().stream().map(ToolId::toString).sorted().forEach(tool -> line(text, " allow", tool));
        line(text, " budget.toolCalls", Integer.toString(agent.budget().toolCalls()));
        line(text, " interactive", Boolean.toString(agent.interactive()));
        line(text, " onTrifecta", agent.onTrifecta().name());
        line(text, " approvalExpiry", agent.approvalExpiry().toString());
        line(text, " reviewers", agent.reviewers().stream().sorted().toArray(String[]::new));
        // Only when set, so policies without service identities keep their hash.
        agent.servicePrincipal().ifPresent(service -> line(text, " principal",
                Stream.concat(Stream.of(service.id(), tenant(service.tenant())), service.roles().stream().sorted())
                        .toArray(String[]::new)));
        agent.downgrades().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolId::toString)))
                .forEach(entry -> line(text, " downgrade", entry.getKey().toString(), entry.getValue().toString()));
        agent.taintRules().stream().map(PolicyHash::taintRule).sorted().forEach(rule -> text.append(' ').append(rule));
        agent.resultRules().stream().map(PolicyHash::resultRule).sorted().forEach(rule -> text.append(' ').append(rule));
        agent.requiredRoles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolId::toString)))
                .forEach(entry -> line(text, " requireRole",
                        Stream.concat(Stream.of(entry.getKey().toString()), entry.getValue().stream().sorted())
                                .toArray(String[]::new)));
        // Only when declared, so policies without bindings keep their hash.
        agent.bindings().values().stream()
                .sorted(Comparator.comparing(Binding::ruleId))
                .forEach(binding -> line(text, " bind", binding.argument().ruleId(), binding.source().toString(),
                        binding.field()));
        agent.argumentRules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ToolArgument::ruleId)))
                .forEach(entry -> {
                    line(text, " rule", entry.getKey().ruleId());
                    entry.getValue().stream().map(PolicyHash::rule).sorted().forEach(rule -> text.append("  ").append(rule));
                });
    }

    private static String tenant(Tenant tenant) {
        return switch (tenant) {
            case Tenant.Scoped scoped -> "tenant " + scoped.id();
            case Tenant.Unscoped unscoped -> "-";
        };
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
            case ArgumentRule.SameTenant(String type) -> line(text, "sameTenant", type);
            case ArgumentRule.OwnedByPrincipal(String type) -> line(text, "ownedByPrincipal", type);
            case ArgumentRule.AtMost(var limit, var action) -> line(text, "atMost", limit.toPlainString(), action.name());
            case ArgumentRule.AtLeast(var limit, var action) -> line(text, "atLeast", limit.toPlainString(), action.name());
            case ArgumentRule.TotalAtMost(var limit, var action) ->
                    line(text, "totalAtMost", limit.toPlainString(), action.name());
            case ArgumentRule.Validated validated -> line(text, "validated", validated.kind(), validated.fingerprint());
        }
        return text.toString();
    }

    private static String resultRule(ResultRule rule) {
        String check = switch (rule.check()) {
            case ResultCheck.MatchesTenant matches -> "matchesTenant";
            case ResultCheck.MatchesPrincipal matches -> "matchesPrincipal";
            case ArgumentRule.SameTenant(String type) -> "sameTenant " + type.length() + ":" + type;
            case ArgumentRule.OwnedByPrincipal(String type) -> "ownedByPrincipal " + type.length() + ":" + type;
        };
        var text = new StringBuilder();
        line(text, "result", rule.tool().toString(), rule.path(), check);
        return text.toString();
    }

    private static String taintRule(TaintRule rule) {
        var text = new StringBuilder();
        line(text, "whenTainted", withLabels(rule.labels(), rule.tool().toString(), rule.action().name()));
        return text.toString();
    }

    /** The leading values, then the label names sorted. */
    private static String[] withLabels(Collection<? extends Enum<?>> labels, String... leading) {
        return Stream.concat(Arrays.stream(leading), labels.stream().map(Enum::name).sorted())
                .toArray(String[]::new);
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
