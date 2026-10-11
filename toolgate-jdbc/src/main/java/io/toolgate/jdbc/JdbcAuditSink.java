package io.toolgate.jdbc;

import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Keeps ToolGate's audit log in the application's database, one row per event, so the application can
 * query what was decided, by whom and why, across every instance: in its own screens, through
 * {@link ToolGateQueries}, or through the {@code toolgate_v1_*} views.
 *
 * <p>Each row holds the event's JSON line, the same as the {@code toolgate.audit} log writes: argument
 * summaries, never argument values, but reasons and comments as written, which can quote them. The
 * columns beside it are for queries. Create the table with {@link JdbcApprovalQueue.Dialect#ddl()},
 * which also creates the queue and session tables and the views, and bound how long rows are kept with
 * {@link #purgeBefore}.
 *
 * <p>Like every audit sink, it runs on ToolGate's audit thread, never on a tool call's path: a slow or
 * failing database delays or drops audit rows, which {@code ToolGate.droppedAuditEvents()} counts, and
 * never a decision.
 */
public final class JdbcAuditSink implements AuditSink {

    /** The table the shipped DDL creates. */
    public static final String TABLE = "toolgate_audit";

    private static final String COLUMNS = "event_id, recorded_at, kind, call_id, approval_id, session_id, agent_id,"
            + " actor_id, tenant, tool, outcome, rule_id, mode, policy_hash, event";

    private final DataSource dataSource;
    private final AtomicBoolean schemaVerified = new AtomicBoolean();

    /**
     * A sink writing to the table the shipped DDL creates.
     *
     * @param dataSource where the table is
     */
    public JdbcAuditSink(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Checks that the table has every column this version writes, so a missed upgrade fails at startup
     * rather than as dropped audit rows. The first write also checks.
     *
     * @throws JdbcStoreException if the table or a column is missing
     */
    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE 1 = 0")) {
            select.executeQuery().close();
            schemaVerified.set(true);
        } catch (SQLException failure) {
            throw new JdbcStoreException("the audit table " + TABLE
                    + " is missing or out of date; create it with JdbcApprovalQueue.Dialect.ddl()", failure);
        }
    }

    @Override
    public void emit(AuditEvent event) {
        if (!schemaVerified.get()) {
            verifySchema();
        }
        Row row = Row.of(event);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setLong(2, Codec.micros(row.at()));
            insert.setString(3, row.kind());
            setText(insert, 4, row.callId());
            setText(insert, 5, row.approvalId());
            setText(insert, 6, row.sessionId());
            setText(insert, 7, row.agentId());
            setText(insert, 8, row.actorId());
            setText(insert, 9, row.tenant());
            setText(insert, 10, row.tool());
            setText(insert, 11, row.outcome());
            setText(insert, 12, row.ruleId());
            setText(insert, 13, row.mode());
            setText(insert, 14, row.policyHash());
            insert.setString(15, event.toJson());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not write a " + row.kind() + " audit row", failure);
        }
    }

    /**
     * Deletes the rows recorded before {@code cutoff}. Run it on a schedule; Spring applications can set
     * {@code toolgate.jdbc.retention} instead.
     *
     * @param cutoff rows recorded before this instant are deleted
     * @return how many rows were deleted
     */
    public int purgeBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE recorded_at < ?")) {
            delete.setLong(1, Codec.micros(cutoff));
            return delete.executeUpdate();
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not purge audit rows", failure);
        }
    }

    private static void setText(PreparedStatement statement, int column, String value) throws SQLException {
        if (value == null) {
            statement.setNull(column, Types.VARCHAR);
        } else {
            statement.setString(column, value);
        }
    }

    /** The queryable columns of one event; the kinds match {@link ToolGateQueries.AuditRow#kind()}. */
    private record Row(Instant at, String kind, String callId, String approvalId, String sessionId, String agentId,
                       String actorId, String tenant, String tool, String outcome, String ruleId, String mode,
                       String policyHash) {

        static Row of(AuditEvent event) {
            return switch (event) {
                case AuditEvent.Decided decided -> new Row(decided.time(), "DECIDED", decided.callId(), approvalId(decided),
                        decided.sessionId(), decided.agentId(), decided.principal().id(), tenant(decided.principal()),
                        decided.tool().toString(), outcome(decided.applied()), decided.applied().ruleId(),
                        lower(decided.mode().name()), decided.policyHash());
                case AuditEvent.ResultWithheld withheld -> new Row(withheld.time(), "WITHHELD", withheld.callId(), null,
                        withheld.sessionId(), withheld.agentId(), withheld.principal().id(), tenant(withheld.principal()),
                        withheld.tool().toString(), withheld.enforced() ? "withheld" : "would_withhold", withheld.ruleId(),
                        null, withheld.policyHash());
                case AuditEvent.ApprovalDecided decided -> new Row(decided.time(), "APPROVAL_DECIDED", null,
                        decided.approvalId(), decided.sessionId(), decided.agentId(), decided.decidedBy(), null,
                        decided.tool().toString(), lower(decided.status().name()), null,
                        decided.access() == null ? null : lower(decided.access().name()), null);
                case AuditEvent.ApprovalRecorded recorded -> new Row(recorded.time(), "APPROVAL_RECORDED", null,
                        recorded.approvalId(), recorded.sessionId(), recorded.agentId(), recorded.decidedBy(), null,
                        recorded.tool().toString(), "recorded", null, lower(recorded.access().name()), null);
                case AuditEvent.ApprovalViewed viewed -> new Row(viewed.time(), "APPROVAL_VIEWED", null,
                        viewed.approvalId(), viewed.sessionId(), viewed.agentId(), viewed.viewer(), null,
                        viewed.tool().toString(), "viewed", null, lower(viewed.access().name()), null);
                case AuditEvent.ApprovalsListed listed -> new Row(listed.time(), "APPROVALS_LISTED", null, null, null,
                        null, listed.viewer(), null, null, "listed", null, lower(listed.access().name()), null);
                case AuditEvent.SuperAdminBurst burst -> new Row(burst.time(), "SUPER_ADMIN_BURST", null, null, null,
                        null, burst.lastBy(), null, null, "burst", null, null, null);
                case AuditEvent.Declassified declassified -> new Row(declassified.time(), "DECLASSIFIED", null, null,
                        declassified.sessionId(), null, null, null, null, lower(declassified.label().name()), null,
                        null, null);
                case AuditEvent.PolicyLoaded loaded -> new Row(loaded.time(), "POLICY_LOADED", null, null, null, null,
                        null, null, null, "loaded", null, lower(loaded.mode().name()), loaded.policyHash());
            };
        }

        private static String approvalId(AuditEvent.Decided decided) {
            return decided.applied() instanceof Decision.Defer defer ? defer.approvalId() : null;
        }

        private static String outcome(Decision decision) {
            return switch (decision) {
                case Decision.Allow allow -> "allow";
                case Decision.Deny deny -> "deny";
                case Decision.Defer defer -> "defer";
                case Decision.Downgrade downgrade -> "downgrade";
                case Decision.Terminate terminate -> "terminate";
            };
        }

        private static String tenant(Principal principal) {
            return switch (principal.tenant()) {
                case Tenant.Scoped(String id) -> id;
                case Tenant.Unscoped unscoped -> null;
            };
        }

        private static String lower(String name) {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
