package io.toolgate.jdbc;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.Execution;
import io.toolgate.api.ListValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.ValueCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * An approval queue in the application's database, so pending calls survive restarts and every
 * instance of the application sees the same queue.
 *
 * <p>A call is released at most once: a status change is an {@code UPDATE ... WHERE status = ?},
 * which succeeds for exactly one of any number of concurrent approvers, on any instance.
 *
 * <p>Create the tables with the DDL for your database, {@link Dialect#ddl()}, through your usual
 * migrations. Decisions are kept in a second table, the queue's name with {@code _decision} appended. The queue
 * checks its tables on first use, or when {@link #verifySchema()} is called at startup, and refuses to
 * work with tables it cannot use. Each row's payload holds the call's real arguments and the deferral reason, which can
 * quote them. Give the queue a {@link ValueCodec} to seal it, bound to the row; without one it is
 * stored in clear, and the queue says so once in the log. An ID longer than its column fails the
 * insert, and ToolGate then denies the call rather than report it as pending.
 */
public final class JdbcApprovalQueue implements ApprovalQueue {

    /** The table name the shipped DDL creates. */
    public static final String DEFAULT_TABLE = "toolgate_pending_call";

    /** SQL state class 23 is an integrity constraint violation in every major database. */
    private static final String INTEGRITY_VIOLATION_CLASS = "23";
    /** At most 54 characters, so the decision table's name, 9 more, fits every database's 63-character limit. */
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,53}");
    private static final String DECISION_SUFFIX = "_decision";
    private static final String INSERT_COLUMNS = "approval_id, status, call_id, session_id, agent_id, principal_kind,"
            + " principal_id, principal_tenant, principal_roles, tool, call_at, defer_rule, payload, deferred_at,"
            + " expires_at";
    private static final String COLUMNS = INSERT_COLUMNS
            + ", version, executor, claimed_at, lease_until, finished_at, outcome";
    private static final String DECISION_COLUMNS = "approval_id, seq, kind, decided_by, access, decided_at, comment";

    /** The databases whose DDL ships with this module. */
    public enum Dialect {
        /** H2, for tests and embedded use. */
        H2,
        /** PostgreSQL. */
        POSTGRESQL,
        /** MySQL with utf8mb4; its primary key holds at most 191 characters. */
        MYSQL;

        /**
         * The statements that create the table, for your migrations.
         *
         * @return the DDL, for the default table name
         */
        public String ddl() {
            String resource = name().toLowerCase(Locale.ROOT) + ".sql";
            try (InputStream in = JdbcApprovalQueue.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing DDL resource " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    private final DataSource dataSource;
    private final String table;
    private final String decisionTable;
    private final ValueCodec codec;
    private final AtomicBoolean schemaVerified = new AtomicBoolean();

    /**
     * A queue in the default table, storing payloads in clear.
     *
     * @param dataSource where the table is
     */
    public JdbcApprovalQueue(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE, Unsealed.warnOnce(DEFAULT_TABLE));
    }

    /**
     * A queue in the default table, sealing payloads with {@code codec}.
     *
     * @param dataSource where the table is
     * @param codec      seals each payload, bound to its row; {@link ValueCodec#unencrypted()} for clear
     */
    public JdbcApprovalQueue(DataSource dataSource, ValueCodec codec) {
        this(dataSource, DEFAULT_TABLE, codec);
    }

    /**
     * A queue in a table of your choosing, created with the shipped DDL under another name.
     *
     * @param dataSource where the table is
     * @param table      a plain SQL identifier: letters, digits and underscores
     * @param codec      seals each payload, bound to its row; {@link ValueCodec#unencrypted()} for clear
     * @throws IllegalArgumentException if the name is not a plain identifier
     */
    public JdbcApprovalQueue(DataSource dataSource, String table, ValueCodec codec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            // The name is put into SQL text, so only plain identifiers are accepted.
            throw new IllegalArgumentException("table name must be a plain SQL identifier, was " + table);
        }
        this.table = table;
        this.decisionTable = table + DECISION_SUFFIX;
    }

    /**
     * Checks that the queue's tables have every column this version uses, so a missed upgrade fails at
     * startup rather than on a reviewer's first click. Every operation also checks, once.
     *
     * @throws JdbcStoreException if a table or column is missing, naming the upgrade to run
     */
    public void verifySchema() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement calls = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE 1 = 0");
             PreparedStatement decisions = connection.prepareStatement(
                     "SELECT " + DECISION_COLUMNS + " FROM " + decisionTable + " WHERE 1 = 0")) {
            calls.executeQuery().close();
            decisions.executeQuery().close();
            schemaVerified.set(true);
        } catch (SQLException failure) {
            throw new JdbcStoreException("the approval queue tables " + table + " and " + decisionTable
                    + " are missing or out of date; create them with JdbcApprovalQueue.Dialect.ddl()", failure);
        }
    }

    private void requireSchema() {
        if (!schemaVerified.get()) {
            verifySchema();
        }
    }

    @Override
    public void add(PendingCall call) {
        requireSchema();
        if (call.status() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("a call enters the queue pending, not " + call.status());
        }
        long deferredAt = micros(call.deferredAt());
        long expiresAt = micros(call.expiresAt());
        if (expiresAt <= deferredAt) {
            throw new IllegalArgumentException("expiry is less than a microsecond after the deferral");
        }
        ToolCall toolCall = call.call();
        String sql = "INSERT INTO " + table + " (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, call.approvalId());
            insert.setString(2, call.status().name());
            insert.setString(3, toolCall.callId());
            insert.setString(4, toolCall.sessionId());
            insert.setString(5, toolCall.agentId());
            insert.setString(6, toolCall.principal().kind().name());
            insert.setString(7, toolCall.principal().id());
            switch (toolCall.principal().tenant()) {
                case Tenant.Scoped(String tenant) -> insert.setString(8, tenant);
                case Tenant.Unscoped unscoped -> insert.setNull(8, Types.VARCHAR);
            }
            insert.setString(9, ArgValueJson.write(ArgValue.list(
                    toolCall.principal().roles().stream().sorted().map(ArgValue::text).toList())));
            insert.setString(10, toolCall.tool().toString());
            insert.setLong(11, micros(toolCall.at()));
            insert.setString(12, call.deferral().ruleId());
            insert.setString(13, codec.seal(payload(toolCall, call.deferral()), rowContext(call.approvalId())));
            insert.setLong(14, deferredAt);
            insert.setLong(15, expiresAt);
            insert.executeUpdate();
        } catch (SQLException failure) {
            // Class 23 covers every integrity violation, not only a duplicate key; only a row that is
            // really there means the call is already queued. Anything else must not pass for success.
            if (isIntegrityViolation(failure) && get(call.approvalId()).isPresent()) {
                return;
            }
            throw new JdbcStoreException("could not queue " + call.approvalId(), failure);
        }
    }

    @Override
    public Optional<PendingCall> get(String approvalId) {
        requireSchema();
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE approval_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, approvalId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(connection, row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read " + approvalId, failure);
        }
    }

    @Override
    public boolean replace(PendingCall expected, PendingCall next) {
        ApprovalQueue.requireNextVersion(expected, next);
        requireSchema();
        String sql = "UPDATE " + table + " SET status = ?, version = ?, executor = ?, claimed_at = ?, lease_until = ?,"
                + " finished_at = ?, outcome = ? WHERE approval_id = ? AND version = ?";
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean replaced = update(connection, sql, expected, next)
                        && insertDecisions(connection, next, expected.decisions().size());
                if (replaced) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return replaced;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not update " + expected.approvalId(), failure);
        }
    }

    @Override
    public List<PendingCall> withStatus(ApprovalStatus status) {
        requireSchema();
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE status = ? ORDER BY deferred_at, approval_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, status.name());
            try (ResultSet rows = select.executeQuery()) {
                var calls = new ArrayList<PendingCall>();
                while (rows.next()) {
                    calls.add(read(connection, rows));
                }
                return calls;
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not list " + status + " calls", failure);
        }
    }

    /** One bounded query; {@code LIMIT ... OFFSET ...} reads the same on H2, PostgreSQL and MySQL. */
    @Override
    public List<PendingCall> find(Set<ApprovalStatus> statuses, Instant deferredSince, int limit, int offset) {
        requireSchema();
        if (statuses.isEmpty() || limit <= 0) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(statuses.size(), "?"));
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE status IN (" + placeholders + ")"
                + " AND deferred_at >= ? ORDER BY deferred_at DESC, approval_id DESC LIMIT ? OFFSET ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (ApprovalStatus status : statuses) {
                select.setString(parameter++, status.name());
            }
            select.setLong(parameter++, micros(deferredSince));
            select.setInt(parameter++, limit);
            select.setInt(parameter, offset);
            try (ResultSet rows = select.executeQuery()) {
                var calls = new ArrayList<PendingCall>();
                while (rows.next()) {
                    calls.add(read(connection, rows));
                }
                return calls;
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not search the approval queue", failure);
        }
    }

    /**
     * Deletes calls that are over, deferred before {@code cutoff}, with their decisions: executed, failed,
     * refused, rejected, expired or cancelled. Calls still waiting, approved, running or with an unknown
     * outcome are kept, whatever their age, since someone still has to act on them. Run it on a schedule;
     * Spring applications can set {@code toolgate.jdbc.retention} instead.
     *
     * @param cutoff calls deferred before this instant are deleted, once they are over
     * @return how many calls were deleted
     */
    public int purgeBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        requireSchema();
        String over = "status IN ('EXECUTED', 'FAILED', 'REFUSED', 'REJECTED', 'EXPIRED', 'CANCELLED') AND deferred_at < ?";
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement decisions = connection.prepareStatement("DELETE FROM " + decisionTable
                         + " WHERE approval_id IN (SELECT approval_id FROM " + table + " WHERE " + over + ")");
                 PreparedStatement calls = connection.prepareStatement("DELETE FROM " + table + " WHERE " + over)) {
                decisions.setLong(1, micros(cutoff));
                decisions.executeUpdate();
                calls.setLong(1, micros(cutoff));
                int purged = calls.executeUpdate();
                connection.commit();
                return purged;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not purge finished calls", failure);
        }
    }

    private boolean update(Connection connection, String sql, PendingCall expected, PendingCall next) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, next.status().name());
            update.setLong(2, next.version());
            Execution run = next.execution();
            if (run == null) {
                for (int column = 3; column <= 6; column++) {
                    update.setNull(column, column == 3 ? Types.VARCHAR : Types.BIGINT);
                }
                update.setNull(7, Types.VARCHAR);
            } else {
                update.setString(3, run.executor());
                update.setLong(4, micros(run.claimedAt()));
                update.setLong(5, micros(run.leaseUntil()));
                if (run.finishedAt() == null) {
                    update.setNull(6, Types.BIGINT);
                    update.setNull(7, Types.VARCHAR);
                } else {
                    update.setLong(6, micros(run.finishedAt()));
                    update.setString(7, codec.seal(run.summary(), outcomeContext(next.approvalId())));
                }
            }
            update.setString(8, expected.approvalId());
            update.setLong(9, expected.version());
            return update.executeUpdate() == 1;
        }
    }

    /** Inserts the decisions {@code next} adds; a key conflict means another writer won, so nothing is replaced. */
    private boolean insertDecisions(Connection connection, PendingCall next, int from) throws SQLException {
        String sql = "INSERT INTO " + decisionTable + " (" + DECISION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int seq = from; seq < next.decisions().size(); seq++) {
                ApprovalDecision decision = next.decisions().get(seq);
                insert.setString(1, next.approvalId());
                insert.setInt(2, seq);
                insert.setString(3, decision.kind().name());
                insert.setString(4, decision.by());
                insert.setString(5, decision.access().name());
                insert.setLong(6, micros(decision.at()));
                if (decision.comment() == null) {
                    insert.setNull(7, Types.VARCHAR);
                } else {
                    insert.setString(7, codec.seal(decision.comment(), decisionContext(next.approvalId(), seq)));
                }
                insert.addBatch();
            }
            insert.executeBatch();
            return true;
        } catch (SQLException failure) {
            if (isIntegrityViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    /** The values a payload carries: the arguments, and the deferral reason, which can quote them. */
    private static String payload(ToolCall call, Decision.Defer deferral) {
        return ArgValueJson.write(ArgValue.object(Map.of("args", call.args(), "reason", ArgValue.text(deferral.reason()))));
    }

    /** Binds a sealed payload to its row: copied into another row, it fails to open. */
    private String rowContext(String approvalId) {
        return table + ":" + approvalId;
    }

    private String outcomeContext(String approvalId) {
        return table + ":" + approvalId + ":outcome";
    }

    private String decisionContext(String approvalId, int seq) {
        return decisionTable + ":" + approvalId + ":" + seq;
    }

    private PendingCall read(Connection connection, ResultSet row) throws SQLException {
        String approvalId = row.getString("approval_id");
        try {
            String tenant = row.getString("principal_tenant");
            var principal = new Principal(Principal.Kind.valueOf(row.getString("principal_kind")),
                    row.getString("principal_id"), tenant == null ? Tenant.none() : Tenant.of(tenant),
                    roles(row.getString("principal_roles")));
            if (!(ArgValueJson.read(codec.open(row.getString("payload"), rowContext(approvalId))) instanceof ObjectValue payload)
                    || !(payload.field("args").orElse(null) instanceof ObjectValue args)
                    || !(payload.field("reason").orElse(null) instanceof TextValue(String reason))) {
                throw new IllegalArgumentException("payload is not {args, reason}");
            }
            var call = new ToolCall(row.getString("call_id"), principal, row.getString("agent_id"),
                    row.getString("session_id"), ToolId.parse(row.getString("tool")), args,
                    instant(row.getLong("call_at")));
            var deferral = new Decision.Defer(row.getString("defer_rule"), reason, approvalId);
            return new PendingCall(approvalId, call, deferral, instant(row.getLong("deferred_at")),
                    instant(row.getLong("expires_at")), ApprovalStatus.valueOf(row.getString("status")),
                    row.getLong("version"), decisions(connection, approvalId), execution(row, approvalId));
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            throw new JdbcStoreException("unreadable queue row " + approvalId, corrupt);
        }
    }

    private Execution execution(ResultSet row, String approvalId) throws SQLException {
        String executor = row.getString("executor");
        if (executor == null) {
            return null;
        }
        long finishedAt = row.getLong("finished_at");
        boolean finished = !row.wasNull();
        String outcome = row.getString("outcome");
        return new Execution(executor, instant(row.getLong("claimed_at")), instant(row.getLong("lease_until")),
                finished ? instant(finishedAt) : null,
                outcome == null ? null : codec.open(outcome, outcomeContext(approvalId)));
    }

    private List<ApprovalDecision> decisions(Connection connection, String approvalId) throws SQLException {
        String sql = "SELECT " + DECISION_COLUMNS + " FROM " + decisionTable + " WHERE approval_id = ? ORDER BY seq";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, approvalId);
            try (ResultSet rows = select.executeQuery()) {
                var decisions = new ArrayList<ApprovalDecision>();
                while (rows.next()) {
                    int seq = rows.getInt("seq");
                    String comment = rows.getString("comment");
                    decisions.add(new ApprovalDecision(ApprovalDecision.Kind.valueOf(rows.getString("kind")),
                            rows.getString("decided_by"), ApprovalAccess.valueOf(rows.getString("access")),
                            instant(rows.getLong("decided_at")),
                            comment == null ? null : codec.open(comment, decisionContext(approvalId, seq))));
                }
                return decisions;
            }
        }
    }

    private static Set<String> roles(String json) {
        if (!(ArgValueJson.read(json) instanceof ListValue(List<ArgValue> elements))) {
            throw new IllegalArgumentException("principal_roles is not a JSON array");
        }
        var roles = new ArrayList<String>();
        for (ArgValue element : elements) {
            if (!(element instanceof TextValue(String role))) {
                throw new IllegalArgumentException("principal_roles holds a non-string");
            }
            roles.add(role);
        }
        return Set.copyOf(roles);
    }

    static boolean isIntegrityViolation(SQLException failure) {
        return failure instanceof SQLIntegrityConstraintViolationException
                || (failure.getSQLState() != null && failure.getSQLState().startsWith(INTEGRITY_VIOLATION_CLASS));
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
}
