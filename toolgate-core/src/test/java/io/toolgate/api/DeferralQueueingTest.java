package io.toolgate.api;

import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.AuditJson;
import io.toolgate.queue.InMemoryApprovalQueue;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.ApprovalStatus;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.PendingCall;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** A deferred call is in the queue by the time check() returns. */
class DeferralQueueingTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:02:00Z");

    private final InMemoryApprovalQueue queue = new InMemoryApprovalQueue();
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Test
    void deferredCallIsQueuedWithItsArgumentsAndExpiry() {
        ToolGate gate = gate(policy(a -> { }));
        taint(gate);

        Decision decision = refund(gate, "c2");

        assertThat(decision).isInstanceOfSatisfying(Decision.Defer.class, defer -> {
            assertThat(defer.approvalId()).isEqualTo("refunds:c2");
            assertThat(defer.ruleId()).isEqualTo(BuiltInRules.TAINT_IRREVERSIBLE);
        });
        PendingCall pending = queue.get("refunds:c2").orElseThrow();
        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.call().args().field("amount")).contains(ArgValue.number(480));
        assertThat(pending.deferral()).isEqualTo(decision);
        assertThat(pending.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void expiryFollowsTheAgent() {
        ToolGate interactive = gate(policy(a -> a.interactive(true)));
        taint(interactive);
        refund(interactive, "c2");
        assertThat(queue.get("refunds:c2").orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));

        InMemoryApprovalQueue other = new InMemoryApprovalQueue();
        ToolGate custom = ToolGate.builder(policy(a -> a.approvalExpiry(Duration.ofHours(2))))
                .approvalQueue(other).clock(Clock.fixed(NOW, ZoneOffset.UTC)).auditSink(event -> { }).build();
        taint(custom);
        refund(custom, "c2");
        assertThat(other.get("refunds:c2").orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    @Test
    void retriedCallIsQueuedOnce() {
        ToolGate gate = gate(policy(a -> { }));
        taint(gate);

        refund(gate, "c2");
        refund(gate, "c2");

        assertThat(queue.pending()).hasSize(1);
    }

    @Test
    void monitorModeQueuesNothing() {
        ToolGate gate = gate(ToolGatePolicy.builder().mode(Mode.MONITOR)
                .tool("fetch", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .agent("refund-agent", a -> a.allow("fetch", "issueRefund"))
                .build());
        taint(gate);

        assertThat(refund(gate, "c2")).isEqualTo(new Decision.Allow(BuiltInRules.MODE_MONITOR));
        assertThat(queue.pending()).isEmpty();
    }

    /** Telling the model an action is pending when nobody will see it would be a lie. */
    @Test
    void failingQueueDeniesTheCall() {
        ApprovalQueue broken = new ApprovalQueue() {
            @Override
            public void add(PendingCall call) {
                throw new IllegalStateException("queue down");
            }

            @Override
            public Optional<PendingCall> get(String approvalId) {
                return Optional.empty();
            }

            @Override
            public boolean transition(String approvalId, ApprovalStatus from, ApprovalStatus to) {
                return false;
            }

            @Override
            public List<PendingCall> pending() {
                return List.of();
            }
        };
        ToolGate gate = ToolGate.builder(policy(a -> { })).approvalQueue(broken).auditSink(event -> { }).build();
        taint(gate);

        assertThat(refund(gate, "c2")).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.APPROVAL_UNAVAILABLE));
    }

    @Test
    void auditNamesTheApproval() {
        try (ToolGate gate = gate(policy(a -> { }))) {
            taint(gate);
            refund(gate, "c2");
        }

        assertThat(events).filteredOn(event -> event instanceof AuditEvent.Decided decided
                        && decided.applied() instanceof Decision.Defer)
                .singleElement()
                .satisfies(event -> assertThat(AuditJson.format(event)).contains("\"approvalId\":\"refunds:c2\""));
    }

    @Test
    void expiryMustBePositiveAndIsPartOfTheHash() {
        assertThatThrownBy(() -> policy(a -> a.approvalExpiry(Duration.ZERO))).isInstanceOf(InvalidPolicyException.class);
        assertThat(policy(a -> a.approvalExpiry(Duration.ofHours(2))).hash()).isNotEqualTo(policy(a -> { }).hash());
    }

    private ToolGate gate(ToolGatePolicy gatePolicy) {
        return ToolGate.builder(gatePolicy).approvalQueue(queue).clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .auditSink(events::add).build();
    }

    /** The refund agent of Case F: refunds are irreversible, so after untrusted content they wait for review. */
    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("fetch", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .agent("refund-agent", a -> {
                    a.allow("fetch", "issueRefund");
                    extra.accept(a);
                })
                .build();
    }

    private static void taint(ToolGate gate) {
        ToolCall read = call("c1", "fetch", Map.of());
        gate.check(read);
        gate.recordResult(read, new ToolResult.Succeeded(ArgValue.text("return request")));
    }

    private static Decision refund(ToolGate gate, String callId) {
        return gate.check(call(callId, "issueRefund", Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))));
    }

    private static ToolCall call(String callId, String tool, Map<String, ArgValue> args) {
        return new ToolCall(callId, Principal.service("refund-agent", Tenant.of("acme")), "refund-agent", "refunds",
                ToolId.local(tool), ObjectValue.of(args), NOW);
    }
}
