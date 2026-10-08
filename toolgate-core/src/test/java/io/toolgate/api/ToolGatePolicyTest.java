package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import org.junit.jupiter.api.Test;

class ToolGatePolicyTest {

    @Test
    void defaultsToEnforcingWithNoAgents() {
        ToolGatePolicy policy = ToolGatePolicy.builder().build();

        assertThat(policy.mode()).isEqualTo(Mode.ENFORCE);
        assertThat(policy.agents()).isEmpty();
        assertThat(policy.denialBudget()).isEqualTo(ToolGatePolicy.DEFAULT_DENIAL_BUDGET);
    }

    @Test
    void ruleModeOverridesThePolicyMode() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .ruleMode(BuiltInRules.TOOL_CALL_BUDGET, Mode.MONITOR)
                .build();

        assertThat(policy.modeFor(BuiltInRules.TOOL_CALL_BUDGET)).isEqualTo(Mode.MONITOR);
        assertThat(policy.modeFor(BuiltInRules.ALLOW_LIST)).isEqualTo(Mode.ENFORCE);
    }

    @Test
    void offTurnsEveryRuleOff() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .mode(Mode.OFF)
                .ruleMode(BuiltInRules.ALLOW_LIST, Mode.ENFORCE)
                .build();

        assertThat(policy.modeFor(BuiltInRules.ALLOW_LIST)).isEqualTo(Mode.OFF);
    }

    /** Turning one rule off would silently skip it: off agent.known even skipped both budgets. */
    @Test
    void rejectsTurningOneRuleOff() {
        assertThatThrownBy(() -> ToolGatePolicy.builder().ruleMode(BuiltInRules.AGENT_KNOWN, Mode.OFF))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("OFF");
        assertThatThrownBy(() -> ToolGatePolicy.builder().ruleMode(BuiltInRules.ALLOW_LIST, Mode.OFF))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void rejectsUnknownRuleId() {
        assertThatThrownBy(() -> ToolGatePolicy.builder().ruleMode("allowlist", Mode.MONITOR))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void rejectsToolFromUnlistedMcpServer() {
        var builder = ToolGatePolicy.builder()
                .mcpServers("jira")
                .agent("scheduler", a -> a.allow("mcp:calendar/create_event"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("calendar");
    }

    @Test
    void rejectsAgentDefinedTwice() {
        var builder = ToolGatePolicy.builder().agent("a", a -> a.allow("x"));

        assertThatThrownBy(() -> builder.agent("a", a -> a.allow("y")))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void rejectsMalformedToolReference() {
        assertThatThrownBy(() -> ToolGatePolicy.builder().agent("a", a -> a.allow("mcp:jira")))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("agent 'a'");
    }

    @Test
    void rejectsLimitsBelowOne() {
        assertThatThrownBy(() -> ToolGatePolicy.builder().defaults(d -> d.denialBudget(0)))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> ToolGatePolicy.builder().agent("a", a -> a.budget(b -> b.toolCalls(0))))
                .isInstanceOf(InvalidPolicyException.class);
    }
}
