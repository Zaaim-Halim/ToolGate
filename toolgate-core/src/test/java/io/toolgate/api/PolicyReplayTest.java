package io.toolgate.api;

import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static io.toolgate.api.policy.ArgumentRules.totalAtMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** History recorded by a live gate under one policy, replayed under another. */
class PolicyReplayTest {

    private static final Principal JOB = Principal.service("job", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final Recording history = new Recording();
    private int callCounter;

    @Test
    void reportsTheCallsANewHostRuleWouldDeny() {
        ToolGate live = live(policy(a -> { }));
        for (String session : List.of("s1", "s2", "s3")) {
            run(live, session, "fetch", Map.of("url", ArgValue.text("https://status.vendor.example/")));
            run(live, session, "fetch", Map.of("url", ArgValue.text("https://supplier.example/terms")));
        }

        ReplayReport report = replay(policy(a -> a.rule("fetch.url", hosts("status.vendor.example"))));

        assertThat(report.sessions()).isEqualTo(3);
        assertThat(report.calls()).isEqualTo(6);
        assertThat(report.changes()).hasSize(3).allSatisfy(change -> {
            assertThat(change.before()).isInstanceOf(Decision.Allow.class);
            assertThat(change.after()).isInstanceOfSatisfying(Decision.Deny.class,
                    deny -> assertThat(deny.ruleId()).isEqualTo("fetch.url"));
            assertThat(change.afterDivergence()).isFalse();
        });
        assertThat(report.summary()).contains("""
                Replayed 6 tool calls in 3 sessions (agents: job)
                Decisions that would change: 3
                        3  local/fetch  allow -> deny  rule fetch.url
                Unchanged: 3
                """);
    }

    /**
     * Denying the read means the session never holds private data: the send is still a draft, but because
     * untrusted content meets an irreversible tool, not the trifecta. A different rule counts as a change.
     */
    @Test
    void aDeniedCallNoLongerTaintsTheCallsAfterIt() {
        ToolGate live = live(policy(a -> { }));
        run(live, "s1", "readInvoice", Map.of("id", ArgValue.text("INV-1")));
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://supplier.example/terms")));
        assertThat(run(live, "s1", "sendEmail", Map.of("to", ArgValue.text("a@b.example"))))
                .isInstanceOf(Decision.Downgrade.class);

        ToolGatePolicy proposed = policy(a -> a.rule("readInvoice.id", sameTenant("invoice")));
        ReplayReport report = PolicyReplay.builder(proposed).toolCatalog(Catalogs.declaring(proposed))
                .ownershipResolver(new OwnershipResolver() { })
                .build().replay(history.events.stream());

        assertThat(report.changes()).extracting(change -> change.tool().name(), change -> change.before().ruleId(),
                change -> change.after().ruleId(), ReplayReport.Change::afterDivergence)
                .containsExactly(tuple("readInvoice", "allow-list", "readInvoice.id", false),
                        tuple("sendEmail", "taint.trifecta", "taint.irreversible", true));
        assertThat(report.summary()).contains("of which 1 come after an earlier change in their session");
    }

    /** The fetch was denied in history; allowed now, it runs, its labels taint the session, and the send becomes a draft. */
    @Test
    void aCallThatRunsOnlyNowTaintsTheCallsAfterIt() {
        ToolGate live = live(policy(a -> a.rule("fetch.url", hosts("status.vendor.example"))));
        run(live, "s1", "readInvoice", Map.of("id", ArgValue.text("INV-1")));
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://supplier.example/terms")));
        assertThat(run(live, "s1", "sendEmail", Map.of("to", ArgValue.text("a@b.example")))).isInstanceOf(Decision.Allow.class);

        ReplayReport report = replay(policy(a -> { }));

        assertThat(report.assumedResults()).isEqualTo(1);
        assertThat(report.changes()).extracting(ReplayReport.Change::after).last()
                .isInstanceOf(Decision.Downgrade.class);
        assertThat(report.summary()).contains("Assumed: 1 calls run only under the proposed policy");
    }

    @Test
    void budgetsAndTotalsBuildUpAsTheyWouldHave() {
        ToolGate live = live(policy(a -> { }));
        for (int refund = 0; refund < 4; refund++) {
            run(live, "s1", "issueRefund", Map.of("amount", ArgValue.number(150)));
        }

        ReplayReport report = replay(policy(a -> a.rule("issueRefund.amount", totalAtMost("400"))));

        assertThat(report.changes()).extracting(ReplayReport.Change::callId).hasSize(2);
        assertThat(report.changes().getFirst().after().ruleId()).isEqualTo("issueRefund.amount");
        assertThat(report.changes().get(1).afterDivergence()).isTrue();
    }

    /** A deferral released in history runs in the replay too, so its amount counts toward the total. */
    @Test
    void releasedDeferralsCountWhenTheyStillDefer() {
        ToolGate live = live(policy(a -> a.reviewers("finance").rule("issueRefund.amount", atMost("200").otherwise(DEFER))));
        Decision first = live.check(call("s1", "issueRefund", Map.of("amount", ArgValue.number(300))));
        assertThat(live.approve(((Decision.Defer) first).approvalId(), LENA, JOB)).isInstanceOf(Approval.Released.class);
        run(live, "s1", "issueRefund", Map.of("amount", ArgValue.number(150)));

        ReplayReport same = replay(policy(a -> a.reviewers("finance").rule("issueRefund.amount", atMost("200").otherwise(DEFER))));
        ReplayReport capped = replay(policy(a -> a.reviewers("finance")
                .rule("issueRefund.amount", atMost("200").otherwise(DEFER), totalAtMost("400"))));

        assertThat(same.changes()).isEmpty();
        assertThat(capped.changes()).singleElement().satisfies(change -> {
            assertThat(change.before()).isInstanceOf(Decision.Allow.class);
            assertThat(change.after().ruleId()).isEqualTo("issueRefund.amount");
        });
    }

    /** Monitor-mode history compares what the old policy computed, not the Allow it applied. */
    @Test
    void monitorModeHistoryComparesComputedDecisions() {
        ToolGate live = live(ToolGatePolicy.builder().mode(Mode.MONITOR)
                .agent("job", a -> a.allow("fetch").rule("fetch.url", hosts("status.vendor.example"))).build());
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://supplier.example/terms")));

        ReplayReport same = replay(ToolGatePolicy.builder()
                .agent("job", a -> a.allow("fetch").rule("fetch.url", hosts("status.vendor.example"))).build());

        assertThat(same.changes()).isEmpty();
    }

    @Test
    void sessionsMayInterleaveAndOwnershipNeedsAResolver() {
        ToolGate live = live(policy(a -> { }));
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://a.example/")));
        run(live, "s2", "fetch", Map.of("url", ArgValue.text("https://a.example/")));
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://a.example/")));

        assertThat(replay(policy(a -> a.budget(b -> b.toolCalls(1)))).changes())
                .singleElement().satisfies(change -> {
                    assertThat(change.sessionId()).isEqualTo("s1");
                    assertThat(change.after()).isInstanceOf(Decision.Terminate.class);
                });
        assertThatIllegalStateException().isThrownBy(() -> PolicyReplay.builder(
                policy(a -> a.rule("readInvoice.id", sameTenant("invoice")))).build());
    }

    /**
     * A call the adapter refused could not be read, so it is not replayed as a check of its empty
     * arguments: it stays refused, never runs, and changes nothing after it.
     */
    @Test
    void aRefusedCallStaysRefused() {
        ToolGate live = live(policy(a -> { }));
        live.reject(call("s1", "sendEmail", Map.of()), io.toolgate.api.policy.BuiltInRules.ARGUMENTS_READABLE, "duplicate key 'to'");
        run(live, "s1", "fetch", Map.of("url", ArgValue.text("https://a.example/")));

        ReplayReport report = replay(policy(a -> { }));

        assertThat(report.changes()).isEmpty();
        assertThat(report.calls()).isEqualTo(2);
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(Effect.READ))
                .tool("fetch", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(Effect.READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("draftEmail", t -> t.effect(Effect.REVERSIBLE))
                .tool("issueRefund", t -> t.effect(Effect.REVERSIBLE))
                .agent("job", a -> {
                    a.allow("readInvoice", "fetch", "sendEmail", "draftEmail", "issueRefund")
                            .downgrade("sendEmail", "draftEmail");
                    extra.accept(a);
                })
                .build();
    }

    private ReplayReport replay(ToolGatePolicy proposed) {
        return PolicyReplay.builder(proposed).toolCatalog(Catalogs.declaring(proposed)).build().replay(history.events.stream());
    }

    private ToolGate live(ToolGatePolicy policy) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).sessionStore(history).auditSink(event -> { }).build();
    }

    private Decision run(ToolGate gate, String session, String tool, Map<String, ArgValue> args) {
        ToolCall call = call(session, tool, args);
        Decision decision = gate.check(call);
        switch (decision) {
            case Decision.Allow allow -> gate.recordResult(call, new ToolResult.Succeeded(ArgValue.text("ok")));
            case Decision.Downgrade downgrade -> gate.recordResult(call.withTool(downgrade.safeTool(), downgrade.safeArgs()),
                    new ToolResult.Succeeded(ArgValue.text("ok")));
            default -> { }
        }
        return decision;
    }

    private ToolCall call(String session, String tool, Map<String, ArgValue> args) {
        return new ToolCall(session + "/" + (++callCounter), JOB, "job", session, ToolId.local(tool), ObjectValue.of(args),
                Instant.parse("2026-10-01T10:00:00Z").plusSeconds(callCounter));
    }

    /** The in-memory store, keeping every event as a durable store's log would. */
    private static final class Recording implements SessionStore {
        private final SessionStore store = SessionStore.inMemory();
        private final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public SessionState state(String sessionId) {
            return store.state(sessionId);
        }

        @Override
        public boolean append(SessionEvent event, long expectedVersion) {
            boolean appended = store.append(event, expectedVersion);
            if (appended) {
                events.add(event);
            }
            return appended;
        }

        @Override
        public void append(SessionEvent event) {
            store.append(event);
            events.add(event);
        }
    }
}
