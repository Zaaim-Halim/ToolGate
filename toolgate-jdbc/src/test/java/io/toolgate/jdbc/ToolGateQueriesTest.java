package io.toolgate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.jdbc.ToolGateQueries.ApprovalStats;
import io.toolgate.spi.PendingCall;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Approval figures from the v1 views, the views' stable columns, and purging finished calls. */
class ToolGateQueriesTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final DataSource database = newDatabase();
    private final JdbcApprovalQueue queue = new JdbcApprovalQueue(database);
    private final ToolGateQueries queries = new ToolGateQueries(database, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void countsWhatWaitsAndWhatBecameOfTheWeeksCalls() {
        queue.add(call("waiting-old", NOW.minus(Duration.ofHours(30))));
        queue.add(call("waiting-new", NOW.minus(Duration.ofMinutes(5))));
        decide("fast", NOW.minus(Duration.ofDays(1)), ApprovalDecision.Kind.APPROVE, Duration.ofMinutes(2), ApprovalStatus.EXECUTED);
        decide("slow", NOW.minus(Duration.ofDays(2)), ApprovalDecision.Kind.REJECT, Duration.ofMinutes(40), ApprovalStatus.REJECTED);
        decide("lost", NOW.minus(Duration.ofDays(3)), ApprovalDecision.Kind.APPROVE, Duration.ofMinutes(10), ApprovalStatus.UNKNOWN);
        decide("withdrawn", NOW.minus(Duration.ofDays(1)), ApprovalDecision.Kind.CANCEL, Duration.ofMinutes(1), ApprovalStatus.CANCELLED);
        decide("last-month", NOW.minus(Duration.ofDays(30)), ApprovalDecision.Kind.APPROVE, Duration.ofHours(9), ApprovalStatus.EXECUTED);

        ApprovalStats stats = queries.stats(Duration.ofDays(7));

        assertThat(stats.waiting()).isEqualTo(2);
        assertThat(stats.oldestWaiting()).isEqualTo(Duration.ofHours(30));
        assertThat(stats.unknown()).isEqualTo(1);
        assertThat(stats.byStatus()).containsExactlyInAnyOrderEntriesOf(Map.of(ApprovalStatus.PENDING, 2,
                ApprovalStatus.EXECUTED, 1, ApprovalStatus.REJECTED, 1, ApprovalStatus.UNKNOWN, 1, ApprovalStatus.CANCELLED, 1));
        assertThat(stats.count(ApprovalStatus.EXPIRED)).isZero();
        // Withdrawals and calls outside the window are not reviewer latency.
        assertThat(stats.latencySamples()).isEqualTo(3);
        assertThat(stats.medianLatency()).isEqualTo(Duration.ofMinutes(10));
        assertThat(stats.p95Latency()).isEqualTo(Duration.ofMinutes(40));
    }

    @Test
    void anEmptyQueueHasZeroFigures() {
        ApprovalStats stats = queries.stats(Duration.ofDays(7));

        assertThat(stats).extracting(ApprovalStats::waiting, ApprovalStats::unknown, ApprovalStats::oldestWaiting,
                ApprovalStats::medianLatency, ApprovalStats::latencySamples)
                .containsExactly(0, 0, Duration.ZERO, Duration.ZERO, 0);
        assertThat(stats.byStatus()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> queries.stats(Duration.ZERO));
    }

    /** The views are a contract: these columns, and nothing sealed or able to quote an argument. */
    @Test
    void theViewsKeepTheirColumnsAndLeaveOutSealedValues() throws SQLException {
        assertThat(columns("toolgate_v1_approvals")).containsExactly("approval_id", "status", "call_id", "session_id",
                "agent_id", "principal_kind", "principal_id", "principal_tenant", "tool", "defer_rule", "deferred_at",
                "expires_at", "executor", "claimed_at", "finished_at");
        assertThat(columns("toolgate_v1_approval_decisions"))
                .containsExactly("approval_id", "seq", "kind", "decided_by", "access", "decided_at");
        assertThat(columns("toolgate_v1_decisions")).containsExactly("event_id", "recorded_at", "call_id", "session_id",
                "agent_id", "principal_id", "principal_tenant", "tool", "outcome", "rule_id", "mode", "policy_hash");
        assertThat(columns("toolgate_v1_sessions"))
                .containsExactly("session_id", "version", "tool_calls", "denials", "updated_at");
    }

    @Test
    void purgesOnlyOldCallsThatAreOver() {
        Instant old = NOW.minus(Duration.ofDays(500));
        decide("old-executed", old, ApprovalDecision.Kind.APPROVE, Duration.ofMinutes(1), ApprovalStatus.EXECUTED);
        decide("old-rejected", old, ApprovalDecision.Kind.REJECT, Duration.ofMinutes(1), ApprovalStatus.REJECTED);
        decide("old-unknown", old, ApprovalDecision.Kind.APPROVE, Duration.ofMinutes(1), ApprovalStatus.UNKNOWN);
        queue.add(call("old-pending", old));
        decide("recent-executed", NOW.minus(Duration.ofDays(3)), ApprovalDecision.Kind.APPROVE, Duration.ofMinutes(1),
                ApprovalStatus.EXECUTED);

        assertThat(queue.purgeBefore(NOW.minus(Duration.ofDays(400)))).isEqualTo(2);

        assertThat(queue.get("old-executed")).isEmpty();
        assertThat(queue.get("old-rejected")).isEmpty();
        assertThat(queue.get("old-unknown")).isPresent();
        assertThat(queue.get("old-pending")).isPresent();
        assertThat(queue.get("recent-executed")).isPresent();
        assertThat(rows("SELECT COUNT(*) FROM toolgate_pending_call_decision")).isEqualTo(2);
    }

    private void decide(String approvalId, Instant deferredAt, ApprovalDecision.Kind kind, Duration after, ApprovalStatus status) {
        PendingCall queued = call(approvalId, deferredAt);
        queue.add(queued);
        var decision = new ApprovalDecision(kind, kind == ApprovalDecision.Kind.CANCEL ? "refund-agent" : "lena",
                kind == ApprovalDecision.Kind.CANCEL ? ApprovalAccess.REQUESTER : ApprovalAccess.REVIEWER,
                deferredAt.plus(after), kind == ApprovalDecision.Kind.APPROVE ? null : "no");
        PendingCall decided = queued.withDecision(decision, status);
        assertThat(queue.replace(queued, decided)).isTrue();
    }

    private static PendingCall call(String approvalId, Instant deferredAt) {
        var call = new ToolCall(approvalId, Principal.service("refund-agent", Tenant.of("acme")), "refund-agent", "s-" + approvalId,
                ToolId.local("issueRefund"), ObjectValue.of(Map.of("amount", ArgValue.number(480))), deferredAt);
        return new PendingCall(approvalId, call, new Decision.Defer("issueRefund.amount", "480 is above 200", approvalId),
                deferredAt, deferredAt.plus(Duration.ofDays(1000)), ApprovalStatus.PENDING);
    }

    private List<String> columns(String view) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT * FROM " + view + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rows.getMetaData();
            var names = new ArrayList<String>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                names.add(meta.getColumnLabel(column).toLowerCase(Locale.ROOT));
            }
            return names;
        }
    }

    private int rows(String sql) {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
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
