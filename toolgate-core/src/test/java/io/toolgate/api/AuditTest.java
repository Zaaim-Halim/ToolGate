package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.DecisionObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** What the audit trail records, through the public API only. */
class AuditTest {

    private static final Instant NOW = Instant.parse("2026-10-08T03:12:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("support-chat", a -> a.allow("fetch").rule("fetch.url", hosts("docs.example"))
                    .budget(b -> b.toolCalls(20)))
            .build();

    @Test
    void recordsThePolicyAndEveryDecision() {
        try (ToolGate gate = gate(policy)) {
            gate.check(fetch("c1", "https://docs.example/a"));
            gate.check(fetch("c2", "https://evil.example/"));
        }

        assertThat(events).hasSize(3);
        assertThat(events.getFirst()).isEqualTo(new AuditEvent.PolicyLoaded(NOW, policy.hash(), Mode.ENFORCE, 1));
        assertThat(events.get(1)).isInstanceOfSatisfying(AuditEvent.Decided.class, decided -> {
            assertThat(decided.time()).isEqualTo(NOW);
            assertThat(decided.callId()).isEqualTo("c1");
            assertThat(decided.principal()).isEqualTo(MARIA);
            assertThat(decided.applied()).isEqualTo(new Decision.Allow("allow-list"));
            assertThat(decided.toolCalls()).isEqualTo(1);
            assertThat(decided.toolCallLimit()).isEqualTo(20);
            assertThat(decided.policyHash()).isEqualTo(policy.hash());
        });
        assertThat(events.get(2)).isInstanceOfSatisfying(AuditEvent.Decided.class, decided -> {
            assertThat(decided.applied()).isInstanceOf(Decision.Deny.class);
            assertThat(decided.applied().ruleId()).isEqualTo("fetch.url");
            assertThat(decided.toolCalls()).isEqualTo(1);
        });
    }

    @Test
    void argumentsAreSummarizedNeverLogged() {
        try (ToolGate gate = gate(policy)) {
            gate.check(fetch("c1", "https://docs.example/private-report"));
        }

        assertThat(decided(1).args()).containsExactly(Map.entry("url", "text(35)"));
    }

    @Test
    void withAKeyArgumentsAreKeyedHashes() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes();
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(events::add).clock(CLOCK).argumentHashKey(key).build()) {
            gate.check(fetch("c1", "https://docs.example/a"));
            gate.check(fetch("c2", "https://docs.example/a"));
        }

        assertThat(decided(1).args().get("url")).startsWith("hmac:").isEqualTo(decided(2).args().get("url"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).argumentHashKey(new byte[16]));
    }

    /** Monitor mode's purpose: show what enforcement would have blocked, without blocking it. */
    @Test
    void monitorModeRecordsTheDecisionItDidNotApply() {
        ToolGatePolicy monitored = ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("fetch").rule("fetch.url", hosts("docs.example")))
                .build();
        try (ToolGate gate = gate(monitored)) {
            assertThat(gate.check(fetch("c1", "http://169.254.169.254/"))).isEqualTo(new Decision.Allow("mode.monitor"));
        }

        assertThat(decided(1).applied()).isEqualTo(new Decision.Allow("mode.monitor"));
        assertThat(decided(1).computed()).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo("fetch.url"));
        assertThat(decided(1).mode()).isEqualTo(Mode.MONITOR);
    }

    @Test
    void budgetEndingTheRunIsAuditedAsATermination() {
        ToolGatePolicy oneCall = ToolGatePolicy.builder()
                .agent("support-chat", a -> a.allow("fetch").budget(b -> b.toolCalls(1)))
                .build();
        try (ToolGate gate = gate(oneCall)) {
            gate.check(fetch("c1", "https://docs.example/"));
            gate.check(fetch("c2", "https://docs.example/"));
        }

        assertThat(decided(2).applied()).isInstanceOfSatisfying(Decision.Terminate.class,
                end -> assertThat(end.ruleId()).isEqualTo("budget.toolCalls"));
        assertThat(decided(2).toolCalls()).isEqualTo(1);
    }

    /** The design's "because": a taint decision names the calls that tainted the session. */
    @Test
    void taintDecisionsNameTheirSources() {
        ToolGatePolicy tainted = ToolGatePolicy.builder()
                .tool("readInvoice", t -> t.labels(Label.PRIVATE_DATA).effect(Effect.READ))
                .tool("fetch", t -> t.labels(Label.UNTRUSTED_CONTENT, Label.EXTERNAL_SINK).effect(Effect.READ))
                .agent("support-chat", a -> a.allow("readInvoice", "fetch"))
                .build();
        try (ToolGate gate = gate(tainted)) {
            ToolCall read = new ToolCall("c1", MARIA, "support-chat", "s1", ToolId.local("readInvoice"),
                    ObjectValue.empty(), NOW);
            gate.check(read);
            gate.recordResult(read, new ToolResult.Succeeded(ArgValue.text("invoice")));
            ToolCall page = fetch("c2", "https://docs.example/a");
            gate.check(page);
            gate.recordResult(page, new ToolResult.Succeeded(ArgValue.text("page")));
            gate.check(fetch("c3", "https://docs.example/b"));
            gate.declassify("s1", Label.UNTRUSTED_CONTENT, "page parsed into a strict schema");
        }

        assertThat(decided(3).applied().ruleId()).isEqualTo("taint.trifecta");
        assertThat(decided(3).taint()).containsOnlyKeys(Label.PRIVATE_DATA, Label.UNTRUSTED_CONTENT);
        assertThat(decided(3).taint().get(Label.UNTRUSTED_CONTENT).callId()).isEqualTo("c2");
        assertThat(events.getLast()).isInstanceOfSatisfying(AuditEvent.Declassified.class,
                declassified -> assertThat(declassified.label()).isEqualTo(Label.UNTRUSTED_CONTENT));
    }

    @Test
    void offModeAuditsNothingButTheLoad() {
        try (ToolGate gate = gate(ToolGatePolicy.builder().mode(Mode.OFF).build())) {
            gate.check(fetch("c1", "https://docs.example/"));
        }

        assertThat(events).singleElement().isInstanceOf(AuditEvent.PolicyLoaded.class);
    }

    @Test
    void afterCloseChecksStillDecideAndEventsAreCounted() {
        ToolGate gate = gate(policy);
        gate.close();

        assertThat(gate.check(fetch("c1", "https://evil.example/"))).isInstanceOf(Decision.Deny.class);
        assertThat(gate.droppedAuditEvents()).isEqualTo(1);
    }

    @Test
    void failingSinkNeverAffectsDecisions() {
        ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> {
            throw new IllegalStateException("SIEM down");
        }).build();

        assertThat(gate.check(fetch("c1", "https://evil.example/"))).isInstanceOf(Decision.Deny.class);
        assertThat(gate.check(fetch("c2", "https://docs.example/"))).isInstanceOf(Decision.Allow.class);
        gate.close();
        assertThat(gate.droppedAuditEvents()).isEqualTo(3);
    }

    @Test
    void everySinkGetsEveryEventAndAFailingOneKeepsNothingFromTheOthers() {
        List<AuditEvent> second = new CopyOnWriteArrayList<>();
        ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog()
                .auditSink(events::add)
                .auditSink(event -> {
                    throw new IllegalStateException("SIEM down");
                })
                .auditSink(second::add).build();
        gate.check(fetch("c1", "https://docs.example/a"));
        gate.close();

        assertThat(events).hasSize(2).isEqualTo(second);
        assertThat(gate.droppedAuditEvents()).as("each event one of the sinks missed").isEqualTo(2);
    }

    @Test
    void withoutTheDefaultLogOrAnySinkNothingIsAuditedAndChecksStillWork() {
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog().build()) {
            assertThat(gate.check(fetch("c1", "https://evil.example/"))).isInstanceOf(Decision.Deny.class);
            assertThat(gate.droppedAuditEvents()).isZero();
        }
    }

    @Test
    void observersSeeEachCheckOnTheCheckingThreadAsItEnds() {
        List<DecisionObserver.Check> checks = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog()
                .decisionObserver(checks::add)
                .decisionObserver(check -> threads.add(Thread.currentThread())).build()) {
            ToolCall allowed = fetch("c1", "https://docs.example/a");
            Decision decision = gate.check(allowed);

            assertThat(checks).singleElement().satisfies(check -> {
                assertThat(check.call()).isEqualTo(allowed);
                assertThat(check.applied()).isEqualTo(decision).isEqualTo(check.computed());
                assertThat(check.mode()).isEqualTo(Mode.ENFORCE);
                assertThat(check.took()).isPositive();
            });
            assertThat(threads).containsExactly(Thread.currentThread());
        }
    }

    @Test
    void inMonitorModeObserversSeeTheDecisionThatWasNotApplied() {
        ToolGatePolicy monitored = ToolGatePolicy.builder().mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("fetch").rule("fetch.url", hosts("docs.example"))).build();
        List<DecisionObserver.Check> checks = new CopyOnWriteArrayList<>();
        try (ToolGate gate = ToolGate.builder(monitored).toolCatalog(Catalogs.declaring(monitored)).noDefaultLog()
                .decisionObserver(checks::add).build()) {
            gate.check(fetch("c1", "http://169.254.169.254/"));
        }

        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.applied()).isEqualTo(new Decision.Allow("mode.monitor"));
            assertThat(check.computed().ruleId()).isEqualTo("fetch.url");
            assertThat(check.mode()).isEqualTo(Mode.MONITOR);
        });
    }

    @Test
    void aFailingObserverNeverChangesADecisionAndIsCountedAsAGap() {
        List<DecisionObserver.Check> later = new CopyOnWriteArrayList<>();
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog()
                .decisionObserver(check -> {
                    throw new IllegalStateException("tracer broken");
                })
                .decisionObserver(later::add).build()) {
            assertThat(gate.check(fetch("c1", "https://evil.example/"))).isInstanceOf(Decision.Deny.class);
            assertThat(gate.check(fetch("c2", "https://docs.example/"))).isInstanceOf(Decision.Allow.class);
            assertThat(gate.droppedAuditEvents()).isEqualTo(2);
        }

        assertThat(later).hasSize(2);
    }

    @Test
    void offModeCallsNoObserver() {
        ToolGatePolicy off = ToolGatePolicy.builder().mode(Mode.OFF).agent("support-chat", a -> a.allow("fetch")).build();
        List<DecisionObserver.Check> checks = new CopyOnWriteArrayList<>();
        try (ToolGate gate = ToolGate.builder(off).noDefaultLog().decisionObserver(checks::add).build()) {
            gate.check(fetch("c1", "https://evil.example/"));
        }

        assertThat(checks).isEmpty();
    }

    private ToolGate gate(ToolGatePolicy gatePolicy) {
        return ToolGate.builder(gatePolicy).toolCatalog(Catalogs.declaring(gatePolicy)).noDefaultLog().auditSink(events::add).clock(CLOCK).build();
    }

    private AuditEvent.Decided decided(int index) {
        return (AuditEvent.Decided) events.get(index);
    }

    private static ToolCall fetch(String callId, String url) {
        return new ToolCall(callId, MARIA, "support-chat", "s1", ToolId.local("fetch"),
                ObjectValue.of(Map.of("url", ArgValue.text(url))), NOW);
    }
}
