package io.toolgate.api;

import static io.toolgate.api.Effect.IRREVERSIBLE;
import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.TaintAction.BLOCK;
import static io.toolgate.api.policy.TaintAction.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.TaintRule;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.api.policy.ToolPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Tool labels and taint settings in the policy: defaults, validation and hashing. */
class TaintPolicyTest {

    @Test
    void undescribedToolsGetSafeDefaults() {
        ToolGatePolicy policy = ToolGatePolicy.builder().build();

        assertThat(policy.tool(ToolId.local("sendEmail"))).isEqualTo(new ToolPolicy(Set.of(), IRREVERSIBLE));
        assertThat(policy.tool(ToolId.mcp("jira", "search_issues")))
                .isEqualTo(new ToolPolicy(Set.of(UNTRUSTED_CONTENT), IRREVERSIBLE));
    }

    @Test
    void describedToolsKeepWhatThePolicySays() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("mcp:jira/get_issue", t -> t.effect(READ))
                .tool("mcp:calendar/list", t -> t.labels().effect(READ))
                .build();

        assertThat(policy.tool(ToolId.local("fetchSupplierPage")))
                .isEqualTo(new ToolPolicy(Set.of(UNTRUSTED_CONTENT, EXTERNAL_SINK), READ));
        assertThat(policy.tool(ToolId.local("sendEmail")).effect()).isEqualTo(IRREVERSIBLE);
        assertThat(policy.tool(ToolId.mcp("jira", "get_issue")).labels())
                .as("an MCP tool keeps its untrusted default unless labels are given")
                .containsExactly(UNTRUSTED_CONTENT);
        assertThat(policy.tool(ToolId.mcp("calendar", "list")).labels())
                .as("an empty labels() clears the default")
                .isEmpty();
    }

    @Test
    void trifectaDefaultsFollowInteractive() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .agent("batch", a -> a.allow("x"))
                .agent("chat", a -> a.allow("x").interactive(true))
                .agent("strict-chat", a -> a.allow("x").interactive(true).onTrifecta(BLOCK))
                .build();

        assertThat(agent(policy, "batch").onTrifecta()).isEqualTo(BLOCK);
        assertThat(agent(policy, "batch").interactive()).isFalse();
        assertThat(agent(policy, "chat").onTrifecta()).isEqualTo(DEFER);
        assertThat(agent(policy, "strict-chat").onTrifecta()).isEqualTo(BLOCK);
    }

    @Test
    void keepsDowngradesAndTaintRules() {
        AgentPolicy agent = agent(policy(a -> a
                .allow("sendEmail", "draftEmail", "issueRefund")
                .downgrade("sendEmail", "draftEmail")
                .whenTainted("issueRefund", UNTRUSTED_CONTENT, PRIVATE_DATA).then(DEFER)), "agent");

        assertThat(agent.downgrades()).containsExactly(Map.entry(ToolId.local("sendEmail"), ToolId.local("draftEmail")));
        assertThat(agent.taintRules()).containsExactly(
                new TaintRule(ToolId.local("issueRefund"), Set.of(UNTRUSTED_CONTENT, PRIVATE_DATA), DEFER));
        assertThat(agent.taintRules().getFirst().ruleId()).isEqualTo("issueRefund.whenTainted");
    }

    @Test
    void rejectsDowngradesThatCannotWork() {
        assertInvalid(a -> a.allow("sendEmail").downgrade("sendEmail", "draftEmail"), "safe tool local/draftEmail");
        assertInvalid(a -> a.allow("draftEmail").downgrade("sendEmail", "draftEmail"), "downgraded tool");
        assertInvalid(a -> a.allow("sendEmail").downgrade("sendEmail", "sendEmail"), "to itself");
        assertInvalid(a -> a.allow("a", "b", "c").downgrade("a", "b").downgrade("b", "c"), "itself downgraded");
        assertInvalid(a -> a.allow("a", "b", "c").downgrade("a", "b").downgrade("a", "c"), "downgraded twice");
    }

    @Test
    void rejectsTaintRulesThatCannotWork() {
        assertInvalid(a -> a.allow("sendEmail").whenTainted("sendEmail", UNTRUSTED_CONTENT), "no action");
        assertInvalid(a -> {
            a.allow("a", "b").whenTainted("a", UNTRUSTED_CONTENT);
            a.whenTainted("b", PRIVATE_DATA).then(BLOCK);
        }, "no action");
        assertInvalid(a -> a.allow("sendEmail").whenTainted("sendEmail", EXTERNAL_SINK).then(BLOCK), "EXTERNAL_SINK");
        assertInvalid(a -> a.allow("draftEmail").whenTainted("sendEmail", UNTRUSTED_CONTENT).then(BLOCK),
                "taint rule tool");
    }

    @Test
    void rejectsToolsDescribedTwice() {
        assertThatThrownBy(() -> ToolGatePolicy.builder()
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("local/sendEmail", t -> t.effect(REVERSIBLE)))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("described twice");
    }

    @Test
    void taintRulesCanBeMonitored() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .ruleMode(BuiltInRules.TAINT_TRIFECTA, Mode.MONITOR)
                .ruleMode(BuiltInRules.TAINT_IRREVERSIBLE, Mode.MONITOR)
                .build();

        assertThat(policy.modeFor(BuiltInRules.TAINT_TRIFECTA)).isEqualTo(Mode.MONITOR);
    }

    @Test
    void hashChangesWithEveryTaintSetting() {
        String base = policy(a -> a.allow("sendEmail", "draftEmail")).hash();
        List<ToolGatePolicy> variants = List.of(
                ToolGatePolicy.builder().tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                        .agent("agent", a -> a.allow("sendEmail", "draftEmail")).build(),
                ToolGatePolicy.builder().tool("sendEmail", t -> t.effect(REVERSIBLE))
                        .agent("agent", a -> a.allow("sendEmail", "draftEmail")).build(),
                policy(a -> a.allow("sendEmail", "draftEmail").interactive(true)),
                policy(a -> a.allow("sendEmail", "draftEmail").onTrifecta(DEFER)),
                policy(a -> a.allow("sendEmail", "draftEmail").downgrade("sendEmail", "draftEmail")),
                policy(a -> a.allow("sendEmail", "draftEmail").whenTainted("sendEmail", UNTRUSTED_CONTENT).then(DEFER)));

        assertThat(variants).extracting(ToolGatePolicy::hash).doesNotContain(base).doesNotHaveDuplicates();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        return ToolGatePolicy.builder().agent("agent", agent).build();
    }

    private static AgentPolicy agent(ToolGatePolicy policy, String id) {
        return policy.agent(id).orElseThrow();
    }

    private static void assertInvalid(Consumer<ToolGatePolicy.AgentBuilder> agent, String message) {
        assertThatThrownBy(() -> policy(agent)).isInstanceOf(InvalidPolicyException.class).hasMessageContaining(message);
    }
}
