package io.toolgate.jdbc;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Effect;
import io.toolgate.api.Label;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** What the module is for: pending calls shared by every instance, and kept across restarts. */
class TwoInstancesTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Principal ROOT = Principal.user("root", Tenant.none(), "toolgate-admin");

    private static final ToolCatalog CATALOG = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"),
            "Refund an order", "{\"type\":\"object\",\"properties\":{\"order\":{},\"amount\":{}}}"));

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                    .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
            .build();
    private final DataSource database = newDatabase();

    @Test
    void oneInstanceDefersAnotherApprovesAndTheFirstCannotReleaseAgain() {
        SessionStore sharedSessions = new JdbcSessionStore(database);
        ToolGate first = instance(sharedSessions);
        ToolGate second = instance(sharedSessions);

        Decision deferred = first.check(refund(480));
        String approvalId = ((Decision.Defer) deferred).approvalId();

        assertThat(second.pending()).extracting(pending -> pending.approvalId()).containsExactly(approvalId);
        assertThat(second.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(first.approve(approvalId, LENA, JOB)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.RELEASED));
    }

    @Test
    void pendingCallsSurviveARestart() {
        SessionStore sessions = new JdbcSessionStore(database);
        String approvalId = ((Decision.Defer) instance(sessions).check(refund(480))).approvalId();

        ToolGate afterRestart = instance(sessions);

        assertThat(afterRestart.pending()).singleElement().satisfies(pending -> {
            assertThat(pending.approvalId()).isEqualTo(approvalId);
            assertThat(pending.call().args().field("amount")).contains(ArgValue.number(480));
        });
        assertThat(afterRestart.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
    }

    /**
     * The gap a shared queue alone leaves: the session gains taint on one instance after the deferral,
     * and the approval on another instance must see it. With per-instance sessions it would not.
     */
    @Test
    void approvalOnAnotherInstanceSeesTaintAddedSinceTheDeferral() {
        ToolGatePolicy tainted = ToolGatePolicy.builder()
                .tool("readReturnRequest", t -> t.labels(Label.UNTRUSTED_CONTENT).effect(Effect.READ))
                .tool("lookupCustomer", t -> t.labels(Label.PRIVATE_DATA).effect(Effect.READ))
                .tool("issueRefund", t -> t.labels(Label.EXTERNAL_SINK))
                .agent("refund-agent", a -> a.allow("readReturnRequest", "lookupCustomer", "issueRefund")
                        .reviewers("finance"))
                .build();
        ToolGate first = instance(tainted, new JdbcSessionStore(database));
        ToolGate second = instance(tainted, new JdbcSessionStore(database));
        run(first, "c1", "readReturnRequest");
        String approvalId = ((Decision.Defer) first.check(refund(480))).approvalId();
        run(first, "c3", "lookupCustomer");

        assertThat(second.approve(approvalId, LENA, JOB)).isInstanceOfSatisfying(Approval.Refused.class,
                refused -> assertThat(refused.reason()).contains("taint.trifecta"));
    }

    /** Two instances, each with its own store object on one database, share one budget. */
    @Test
    void parallelCallsOnTwoInstancesCannotOvershootTheBudget() throws Exception {
        ToolGatePolicy budgeted = ToolGatePolicy.builder()
                .agent("refund-agent", a -> a.allow("lookup").budget(b -> b.toolCalls(40)))
                .build();
        List<ToolGate> instances = List.of(instance(budgeted, new JdbcSessionStore(database)),
                instance(budgeted, new JdbcSessionStore(database)));
        var calls = new ArrayList<Callable<Decision>>();
        for (int index = 0; index < 120; index++) {
            ToolGate gate = instances.get(index % 2);
            String callId = "p" + index;
            calls.add(() -> gate.check(new ToolCall(callId, JOB, "refund-agent", "busy", ToolId.local("lookup"),
                    ObjectValue.empty(), Instant.EPOCH)));
        }

        var decisions = new ArrayList<Decision>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (Future<Decision> decision : executor.invokeAll(calls)) {
                decisions.add(decision.get());
            }
        }

        assertThat(decisions).filteredOn(Decision.Allow.class::isInstance).hasSize(40);
        assertThat(decisions).filteredOn(Decision.Terminate.class::isInstance).hasSize(80)
                .allSatisfy(decision -> assertThat(decision.ruleId()).isEqualTo("budget.toolCalls"));
    }

    /** The burst alert counts every instance's super admin decisions, from the shared decision table. */
    @Test
    void aSuperAdminBurstSpreadOverInstancesIsSeen() {
        ToolGatePolicy withSuperAdmins = ToolGatePolicy.builder().superAdmins("toolgate-admin")
                .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .build();
        var events = new CopyOnWriteArrayList<AuditEvent>();
        SessionStore sharedSessions = new JdbcSessionStore(database);
        ToolGate first = alerting(withSuperAdmins, sharedSessions, events);
        ToolGate second = alerting(withSuperAdmins, sharedSessions, events);

        first.reject(defer(first, "c1"), ROOT, "cleanup");
        second.reject(defer(second, "c2"), ROOT, "cleanup");
        first.reject(defer(first, "c3"), ROOT, "cleanup");
        first.close();
        second.close();

        assertThat(events).filteredOn(AuditEvent.SuperAdminBurst.class::isInstance).singleElement()
                .satisfies(burst -> assertThat(((AuditEvent.SuperAdminBurst) burst).decisions()).isEqualTo(3));
    }

    /** An instance that alerts on a third super admin decision within an hour. */
    private ToolGate alerting(ToolGatePolicy gatePolicy, SessionStore sessions, List<AuditEvent> events) {
        return ToolGate.builder(gatePolicy).toolCatalog(CATALOG).approvalQueue(new JdbcApprovalQueue(database))
                .sessionStore(sessions).noDefaultLog().superAdminAlert(2, Duration.ofHours(1)).auditSink(events::add)
                .build();
    }

    private static String defer(ToolGate gate, String callId) {
        var call = new ToolCall(callId, JOB, "refund-agent", "refunds-" + callId, ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))), Instant.EPOCH);
        return ((Decision.Defer) gate.check(call)).approvalId();
    }

    private ToolGate instance(SessionStore sessions) {
        return instance(policy, sessions);
    }

    private ToolGate instance(ToolGatePolicy gatePolicy, SessionStore sessions) {
        return ToolGate.builder(gatePolicy)
                .toolCatalog(CATALOG)
                .approvalQueue(new JdbcApprovalQueue(database))
                .sessionStore(sessions)
                .noDefaultLog()
                .build();
    }

    private static void run(ToolGate gate, String callId, String tool) {
        ToolCall call = new ToolCall(callId, JOB, "refund-agent", "refunds", ToolId.local(tool), ObjectValue.empty(),
                Instant.EPOCH);
        gate.check(call);
        gate.recordResult(call, new ToolResult.Succeeded(ArgValue.text("ok")));
    }

    private static ToolCall refund(int amount) {
        return new ToolCall("c1", JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(amount))), Instant.EPOCH);
    }

    /**
     * A fresh database with ToolGate's tables. H2 here; the PostgreSQL and MySQL suites override it.
     *
     * @return the database
     */
    DataSource newDatabase() {
        return H2.withQueueTable();
    }
}
