package io.toolgate.jdbc;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.ListValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.PendingCall;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * An approval queue in the application's database, so pending calls survive restarts and every
 * instance of the application sees the same queue.
 *
 * <p>A call is released at most once: a status change is an {@code UPDATE ... WHERE status = ?},
 * which succeeds for exactly one of any number of concurrent approvers, on any instance.
 *
 * <p>Create the table with the DDL for your database, {@link Dialect#ddl()}, through your usual
 * migrations. The table holds the calls' real arguments, as JSON: protect it like the data it
 * carries. An ID longer than its column fails the insert, and ToolGate then denies the call rather
 * than report it as pending.
 */
public final class JdbcApprovalQueue implements ApprovalQueue {

    /** The table name the shipped DDL creates. */
    public static final String DEFAULT_TABLE = "toolgate_pending_call";

    /** SQL state class 23 is an integrity constraint violation in every major database. */
    private static final String INTEGRITY_VIOLATION_CLASS = "23";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final String COLUMNS = "approval_id, status, call_id, session_id, agent_id, principal_kind,"
            + " principal_id, principal_tenant, principal_roles, tool, args, call_at, defer_rule, defer_reason,"
            + " deferred_at, expires_at";

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

    /**
     * A queue in the default table.
     *
     * @param dataSource where the table is
     */
    public JdbcApprovalQueue(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    /**
     * A queue in a table of your choosing, created with the shipped DDL under another name.
     *
     * @param dataSource where the table is
     * @param table      a plain SQL identifier: letters, digits and underscores
     * @throws IllegalArgumentException if the name is not a plain identifier
     */
    public JdbcApprovalQueue(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            // The name is put into SQL text, so only plain identifiers are accepted.
            throw new IllegalArgumentException("table name must be a plain SQL identifier, was " + table);
        }
        this.table = table;
    }

    @Override
    public void add(PendingCall call) {
        if (call.status() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("a call enters the queue pending, not " + call.status());
        }
        long deferredAt = micros(call.deferredAt());
        long expiresAt = micros(call.expiresAt());
        if (expiresAt <= deferredAt) {
            throw new IllegalArgumentException("expiry is less than a microsecond after the deferral");
        }
        ToolCall toolCall = call.call();
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
            insert.setString(11, ArgValueJson.write(toolCall.args()));
            insert.setLong(12, micros(toolCall.at()));
            insert.setString(13, call.deferral().ruleId());
            insert.setString(14, call.deferral().reason());
            insert.setLong(15, deferredAt);
            insert.setLong(16, expiresAt);
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
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE approval_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, approvalId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read " + approvalId, failure);
        }
    }

    @Override
    public boolean transition(String approvalId, ApprovalStatus from, ApprovalStatus to) {
        String sql = "UPDATE " + table + " SET status = ? WHERE approval_id = ? AND status = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, to.name());
            update.setString(2, approvalId);
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not move " + approvalId + " to " + to, failure);
        }
    }

    @Override
    public List<PendingCall> pending() {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE status = ? ORDER BY deferred_at, approval_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, ApprovalStatus.PENDING.name());
            try (ResultSet rows = select.executeQuery()) {
                var calls = new ArrayList<PendingCall>();
                while (rows.next()) {
                    calls.add(read(rows));
                }
                return calls;
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not list pending calls", failure);
        }
    }

    private static PendingCall read(ResultSet row) throws SQLException {
        String approvalId = row.getString("approval_id");
        try {
            String tenant = row.getString("principal_tenant");
            var principal = new Principal(Principal.Kind.valueOf(row.getString("principal_kind")),
                    row.getString("principal_id"), tenant == null ? Tenant.none() : Tenant.of(tenant),
                    roles(row.getString("principal_roles")));
            if (!(ArgValueJson.read(row.getString("args")) instanceof ObjectValue args)) {
                throw new IllegalArgumentException("args is not a JSON object");
            }
            var call = new ToolCall(row.getString("call_id"), principal, row.getString("agent_id"),
                    row.getString("session_id"), ToolId.parse(row.getString("tool")), args,
                    instant(row.getLong("call_at")));
            var deferral = new Decision.Defer(row.getString("defer_rule"), row.getString("defer_reason"), approvalId);
            return new PendingCall(approvalId, call, deferral, instant(row.getLong("deferred_at")),
                    instant(row.getLong("expires_at")), ApprovalStatus.valueOf(row.getString("status")));
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            throw new JdbcStoreException("unreadable queue row " + approvalId, corrupt);
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

    private static boolean isIntegrityViolation(SQLException failure) {
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
