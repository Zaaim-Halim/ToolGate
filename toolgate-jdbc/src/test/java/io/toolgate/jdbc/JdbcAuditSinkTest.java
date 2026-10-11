package io.toolgate.jdbc;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.jdbc.ToolGateQueries.AuditQuery;
import io.toolgate.jdbc.ToolGateQueries.AuditRow;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.ToolCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The audit log as rows: what a gate writes, how the application reads it back, and how it is purged. */
class JdbcAuditSinkTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");

    private final H2 database = H2.withQueueTable();
    private final JdbcAuditSink sink = new JdbcAuditSink(database);
    private final ToolGateQueries queries = new ToolGateQueries(database);

    @Test
    void recordsEveryDecisionOfAGateWithItsColumns() {
        var catalog = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"), "Refund an order",
                "{\"type\":\"object\",\"properties\":{\"order\":{},\"amount\":{}}}"));
        var policy = ToolGatePolicy.builder()
                .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .build();
        ToolGate gate = ToolGate.builder(policy).toolCatalog(catalog).approvalQueue(new JdbcApprovalQueue(database))
                .auditSink(sink).noDefaultLog().build();
        var call = new ToolCall("c1", JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))), T0);

        String approvalId = ((Decision.Defer) gate.check(call)).approvalId();
        gate.approve(approvalId, LENA, JOB);
        gate.close();

        assertThat(gate.droppedAuditEvents()).isZero();
        AuditRow deferral = single(queries.audit(AuditQuery.latest().kinds("DECIDED")));
        assertThat(deferral).extracting(AuditRow::callId, AuditRow::approvalId, AuditRow::sessionId, AuditRow::agentId,
                        AuditRow::actorId, AuditRow::tenant, AuditRow::tool, AuditRow::outcome, AuditRow::ruleId,
                        AuditRow::mode)
                .containsExactly("c1", approvalId, "refunds", "refund-agent", "refund-agent", "acme",
                        "local/issueRefund", "defer", "issueRefund.amount", "enforce");
        assertThat(deferral.event()).startsWith("{\"event\":\"toolgate.decision\"").doesNotContain("7731");
        AuditRow released = single(queries.audit(AuditQuery.latest().approval(approvalId).kinds("APPROVAL_DECIDED")));
        assertThat(released).extracting(AuditRow::actorId, AuditRow::outcome, AuditRow::mode)
                .containsExactly("lena", "released", "reviewer");
    }

    @Test
    void readsNewestFirstThroughEveryFilter() {
        sink.emit(decided(T0, "s1"));
        sink.emit(decided(T0.plusSeconds(60), "s2"));
        sink.emit(rejected(T0.plusSeconds(120), "s1"));

        assertThat(queries.audit(AuditQuery.latest())).extracting(AuditRow::at)
                .containsExactly(T0.plusSeconds(120), T0.plusSeconds(60), T0);
        assertThat(queries.audit(AuditQuery.latest().session("s1"))).extracting(AuditRow::kind)
                .containsExactly("APPROVAL_DECIDED", "DECIDED");
        assertThat(queries.audit(AuditQuery.latest().outcome("rejected").actor("lena"))).hasSize(1);
        assertThat(queries.audit(AuditQuery.latest().since(T0.plusSeconds(60)).until(T0.plusSeconds(120))))
                .extracting(AuditRow::sessionId).containsExactly("s2");
        assertThat(queries.audit(AuditQuery.latest().tool("local/issueRefund").agent("refund-agent").limit(1)))
                .extracting(AuditRow::at).containsExactly(T0.plusSeconds(120));
        assertThat(queries.audit(AuditQuery.latest().call("nobody"))).isEmpty();
    }

    @Test
    void purgesOnlyRowsOlderThanTheCutoff() {
        sink.emit(decided(T0, "old"));
        sink.emit(decided(T0.plus(Duration.ofDays(2)), "new"));

        assertThat(sink.purgeBefore(T0.plus(Duration.ofDays(1)))).isEqualTo(1);

        assertThat(queries.audit(AuditQuery.latest())).extracting(AuditRow::sessionId).containsExactly("new");
    }

    @Test
    void refusesToWorkWithoutItsTable() {
        var empty = new H2();

        assertThatThrownBy(() -> new JdbcAuditSink(empty).emit(decided(T0, "s1")))
                .isInstanceOf(JdbcStoreException.class).hasMessageContaining("Dialect.ddl()");
    }

    @Test
    void rejectsAQueryOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> AuditQuery.latest().limit(0));
        assertThatIllegalArgumentException().isThrownBy(() -> AuditQuery.latest().limit(ToolGateQueries.MAX_AUDIT_ROWS + 1));
        assertThatIllegalArgumentException().isThrownBy(() -> AuditQuery.latest().session(" "));
    }

    private static AuditEvent.Decided decided(Instant at, String sessionId) {
        return new AuditEvent.Decided(at, "c-" + at.getEpochSecond(), sessionId, "refund-agent", JOB,
                ToolId.local("issueRefund"), Map.of("amount", "number"), new Decision.Allow("allow-list"),
                new Decision.Allow("allow-list"), Mode.ENFORCE, 1, Integer.MAX_VALUE, Map.of(), "hash");
    }

    private static AuditEvent.ApprovalDecided rejected(Instant at, String sessionId) {
        return new AuditEvent.ApprovalDecided(at, "a-1", sessionId, "refund-agent", ToolId.local("issueRefund"),
                ApprovalStatus.REJECTED, "lena", ApprovalAccess.REVIEWER, "not this order");
    }

    private static AuditRow single(List<AuditRow> rows) {
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
}
