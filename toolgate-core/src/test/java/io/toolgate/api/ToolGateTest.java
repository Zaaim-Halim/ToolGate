package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Exercises the gate only through the exported API, as an adapter would. */
class ToolGateTest {

    private static final Instant NOW = Instant.parse("2026-10-07T19:42:11Z");
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    private final AtomicInteger callCounter = new AtomicInteger();

    @Test
    void allowsListedTool() {
        ToolGate gate = gate(policy(a -> a.allow("readInvoice")));

        assertThat(gate.check(call("agent", "s1", ToolId.local("readInvoice"))))
                .isEqualTo(new Decision.Allow(BuiltInRules.ALLOW_LIST));
    }

    @Test
    void deniesUnlistedTool() {
        ToolGate gate = gate(policy(a -> a.allow("readInvoice")));

        assertThat(gate.check(call("agent", "s1", ToolId.local("exportCustomers"))))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.ALLOW_LIST));
    }

    @Test
    void deniesUnknownAgent() {
        ToolGate gate = gate(policy(a -> a.allow("readInvoice")));

        assertThat(gate.check(call("intruder", "s1", ToolId.local("readInvoice"))))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.AGENT_KNOWN));
    }

    /** Case D: a policy written by tool name must not approve a remote tool with that name. */
    @Test
    void remoteToolCannotPassForLocalToolWithSameName() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .mcpServers("calendar")
                .agent("scheduling-agent", a -> a.allow("sendEmail", "mcp:calendar/create_event"))
                .build());

        assertThat(gate.check(call("scheduling-agent", "s1", ToolId.mcp("calendar", "sendEmail"))))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.reason()).contains("mcp:calendar/sendEmail"));
        assertThat(gate.check(call("scheduling-agent", "s1", ToolId.mcp("calendar", "create_event"))))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void deniesToolsFromUnlistedMcpServer() {
        ToolGate gate = gate(policy(a -> a.allow("readInvoice")));

        assertThat(gate.check(call("agent", "s1", ToolId.mcp("calendar", "create_event"))))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.MCP_SERVERS));
    }

    /** Case B: five denials are answered; the next call, whatever it is, ends the run. */
    @Test
    void callAfterDenialBudgetEndsTheRun() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .defaults(d -> d.denialBudget(5))
                .agent("support-chat", a -> a.allow("getInvoice"))
                .build());

        for (int probe = 0; probe < 5; probe++) {
            assertThat(gate.check(call("support-chat", "maria-chat", ToolId.local("adminExport"))))
                    .isInstanceOf(Decision.Deny.class);
        }

        assertThat(gate.check(call("support-chat", "maria-chat", ToolId.local("getInvoice"))))
                .isInstanceOfSatisfying(Decision.Terminate.class,
                        end -> assertThat(end.ruleId()).isEqualTo(BuiltInRules.DENIAL_BUDGET));
    }

    /** Case E: a budget of 40 ends the poisoned run at call 41, and the next ticket is unaffected. */
    @Test
    void callPastToolCallBudgetEndsTheRun() {
        ToolGate gate = gate(policy(a -> a.allow("lookupCustomer").budget(b -> b.toolCalls(40))));

        for (int lookup = 1; lookup <= 40; lookup++) {
            assertThat(gate.check(call("agent", "ticket-48213", ToolId.local("lookupCustomer"))))
                    .as("call %d", lookup)
                    .isInstanceOf(Decision.Allow.class);
        }

        assertThat(gate.check(call("agent", "ticket-48213", ToolId.local("lookupCustomer"))))
                .isInstanceOfSatisfying(Decision.Terminate.class,
                        end -> assertThat(end.ruleId()).isEqualTo(BuiltInRules.TOOL_CALL_BUDGET));
        assertThat(gate.check(call("agent", "ticket-48214", ToolId.local("lookupCustomer"))))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void deniedCallsDoNotSpendToolCallBudget() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .defaults(d -> d.denialBudget(10))
                .agent("agent", a -> a.allow("lookupCustomer").budget(b -> b.toolCalls(2)))
                .build());

        gate.check(call("agent", "s1", ToolId.local("notAllowed")));
        gate.check(call("agent", "s1", ToolId.local("notAllowed")));

        assertThat(gate.check(call("agent", "s1", ToolId.local("lookupCustomer")))).isInstanceOf(Decision.Allow.class);
        assertThat(gate.check(call("agent", "s1", ToolId.local("lookupCustomer")))).isInstanceOf(Decision.Allow.class);
    }

    @RepeatedTest(20)
    void parallelCallsCannotOvershootTheBudget() throws InterruptedException, ExecutionException {
        ToolGate gate = gate(policy(a -> a.allow("lookupCustomer").budget(b -> b.toolCalls(40))));

        List<Decision> decisions = checkInParallel(gate, 200, ToolId.local("lookupCustomer"));

        assertThat(decisions).filteredOn(Decision.Allow.class::isInstance).hasSize(40);
        assertThat(decisions).filteredOn(Decision.Terminate.class::isInstance).hasSize(160);
    }

    @RepeatedTest(20)
    void parallelDenialsCannotOvershootTheDenialBudget() throws InterruptedException, ExecutionException {
        ToolGate gate = gate(policy(a -> a.allow("lookupCustomer")));

        List<Decision> decisions = checkInParallel(gate, 200, ToolId.local("exportCustomers"));

        assertThat(decisions).filteredOn(Decision.Deny.class::isInstance).hasSize(ToolGatePolicy.DEFAULT_DENIAL_BUDGET);
        assertThat(decisions).filteredOn(Decision.Terminate.class::isInstance)
                .hasSize(200 - ToolGatePolicy.DEFAULT_DENIAL_BUDGET);
    }

    @RepeatedTest(20)
    void parallelCallsWithoutBudgetAreAllAllowed() throws InterruptedException, ExecutionException {
        ToolGate gate = gate(policy(a -> a.allow("lookupCustomer")));

        List<Decision> decisions = checkInParallel(gate, 200, ToolId.local("lookupCustomer"));

        assertThat(decisions).allMatch(Decision.Allow.class::isInstance);
    }

    @Test
    void monitorModeAllowsButNamesTheMonitor() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("agent", a -> a.allow("readInvoice"))
                .build());

        assertThat(gate.check(call("agent", "s1", ToolId.local("exportCustomers"))))
                .isEqualTo(new Decision.Allow(BuiltInRules.MODE_MONITOR));
        assertThat(gate.check(call("agent", "s1", ToolId.local("readInvoice"))))
                .isEqualTo(new Decision.Allow(BuiltInRules.ALLOW_LIST));
    }

    @Test
    void oneRuleCanBeMonitoredWhileTheRestIsEnforced() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .ruleMode(BuiltInRules.TOOL_CALL_BUDGET, Mode.MONITOR)
                .agent("agent", a -> a.allow("lookupCustomer").budget(b -> b.toolCalls(1)))
                .build());

        gate.check(call("agent", "s1", ToolId.local("lookupCustomer")));

        assertThat(gate.check(call("agent", "s1", ToolId.local("lookupCustomer"))))
                .isEqualTo(new Decision.Allow(BuiltInRules.MODE_MONITOR));
        assertThat(gate.check(call("agent", "s1", ToolId.local("exportCustomers"))))
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void offAllowsEverything() {
        ToolGate gate = gate(ToolGatePolicy.builder().mode(Mode.OFF).build());

        assertThat(gate.check(call("anyone", "s1", ToolId.local("anything"))))
                .isEqualTo(new Decision.Allow(BuiltInRules.MODE_OFF));
    }

    private List<Decision> checkInParallel(ToolGate gate, int count, ToolId tool)
            throws InterruptedException, ExecutionException {
        var calls = new ArrayList<Callable<Decision>>();
        for (int i = 0; i < count; i++) {
            calls.add(() -> gate.check(call("agent", "shared", tool)));
        }
        var decisions = new ArrayList<Decision>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Decision> future : executor.invokeAll(calls)) {
                decisions.add(future.get());
            }
        }
        return decisions;
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).build();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        return ToolGatePolicy.builder().agent("agent", agent).build();
    }

    private ToolCall call(String agentId, String sessionId, ToolId tool) {
        return new ToolCall(sessionId + "/" + callCounter.incrementAndGet(), MARIA, agentId, sessionId,
                tool, ObjectValue.empty(), NOW);
    }
}
