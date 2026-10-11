package io.toolgate.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The Micrometer decision counter and check timer, on a real gate. */
class MicrometerTest {

    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void countsEachDecisionByOutcomeRuleAgentAndTool() {
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .agent("support-chat", a -> a.allow("getInvoice").budget(b -> b.toolCalls(2))).build();
        try (ToolGate gate = gate(policy)) {
            gate.check(call("c1", "getInvoice"));
            gate.check(call("c2", "getInvoice"));
            gate.check(call("c3", "getInvoice"));
        }

        assertThat(count("outcome", "allow", "rule", "allow-list", "computed", "allow", "computedRule", "allow-list",
                "agent", "support-chat", "tool", "local/getInvoice")).isEqualTo(2);
        assertThat(count("outcome", "terminate", "rule", "budget.toolCalls", "tool", "local/getInvoice")).isEqualTo(1);
    }

    /** A model can call a tool by any name; each must not become its own time series. */
    @Test
    void toolsThePolicyDoesNotListShareOneTag() {
        ToolGatePolicy policy = ToolGatePolicy.builder().agent("support-chat", a -> a.allow("getInvoice")).build();
        try (ToolGate gate = gate(policy)) {
            gate.check(call("c1", "deleteEverything"));
            gate.check(call("c2", "exportCustomers"));
        }

        assertThat(count("outcome", "deny", "rule", "allow-list", "tool", MicrometerAuditSink.UNLISTED)).isEqualTo(2);
        assertThat(registry.find(MicrometerAuditSink.DECISIONS).counters()).hasSize(1);
    }

    @Test
    void inMonitorModeTheComputedTagsShowWhatEnforcementWouldDo() {
        ToolGatePolicy policy = ToolGatePolicy.builder().mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("getInvoice")).build();
        try (ToolGate gate = gate(policy)) {
            gate.check(call("c1", "deleteEverything"));
        }

        assertThat(count("outcome", "allow", "rule", "mode.monitor", "computed", "deny", "computedRule", "allow-list"))
                .isEqualTo(1);
    }

    @Test
    void timesEachCheckByOutcomeAndAgent() {
        ToolGatePolicy policy = ToolGatePolicy.builder().agent("support-chat", a -> a.allow("getInvoice")).build();
        try (ToolGate gate = ToolGate.builder(policy).noDefaultLog().decisionObserver(new MicrometerCheckTimer(registry)).build()) {
            gate.check(call("c1", "getInvoice"));
            gate.check(call("c2", "deleteEverything"));
        }

        var allowed = registry.get(MicrometerCheckTimer.CHECK_DURATION).tags("outcome", "allow", "agent", "support-chat").timer();
        var denied = registry.get(MicrometerCheckTimer.CHECK_DURATION).tags("outcome", "deny", "agent", "support-chat").timer();
        assertThat(allowed.count()).isEqualTo(1);
        assertThat(denied.count()).isEqualTo(1);
        assertThat(allowed.totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    private ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).noDefaultLog().auditSink(new MicrometerAuditSink(registry, policy)).build();
    }

    /** The decision counters matching the tags, summed; read after the gate closed, so all are delivered. */
    private double count(String... tags) {
        Collection<Counter> counters = registry.find(MicrometerAuditSink.DECISIONS).tags(tags).counters();
        assertThat(counters).as("counters tagged %s", List.of(tags)).isNotEmpty();
        return counters.stream().mapToDouble(Counter::count).sum();
    }

    private static ToolCall call(String callId, String tool) {
        return new ToolCall(callId, MARIA, "support-chat", "s1", ToolId.local(tool),
                ObjectValue.of(Map.of("id", ArgValue.text("INV-118"))), Instant.parse("2026-10-11T10:00:00Z"));
    }
}
