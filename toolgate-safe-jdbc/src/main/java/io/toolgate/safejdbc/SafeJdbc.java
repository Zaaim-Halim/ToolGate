package io.toolgate.safejdbc;

import io.toolgate.api.ArgValue;
import io.toolgate.spi.ArgumentValidator;
import io.toolgate.sql.SqlRules;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Runs a query a model wrote, for a tool such as {@code runQuery(String sql)}:
 *
 * <pre>{@code
 * Rows rows = SafeJdbc.query(dataSource, sql, Limits.defaults());
 * }</pre>
 *
 * <p>The query first passes the same pre-check as the {@code readOnlySql} rule, then runs in a
 * transaction the database itself holds to read-only, with a timeout, and is always rolled back.
 * Only PostgreSQL, MySQL and MariaDB can be made read-only this way; on any other database,
 * {@link #query} refuses, and {@link #queryWithReadOnlyRole} runs the query if the caller vouches
 * that the connection's database role may only read. Such a role is the stronger protection on
 * every database, and recommended alongside SafeJdbc.
 *
 * <p>What a read-only transaction does not stop: functions with side effects outside the
 * transaction, such as PostgreSQL's {@code pg_terminate_backend} or {@code dblink}; reads of
 * tables the role can see; and reads of server files, such as {@code pg_read_file} or MySQL's
 * {@code LOAD_FILE}. Grant the role only what the tool needs, and neither
 * {@code pg_read_server_files} nor MySQL's {@code FILE} privilege.
 *
 * <p>The database is recognized by the product name its driver reports. A compatible database
 * that reports {@code PostgreSQL} or {@code MySQL}, and accepts the read-only statement without
 * enforcing it, would not be noticed.
 *
 * <p>The {@link Limits} bound what reaches the model, not what the driver loads: a long value is
 * read whole before it is cut, and some drivers, such as PostgreSQL's, buffer whole rows. The
 * timeout and the database's own resource limits bound the rest.
 */
public final class SafeJdbc {

    private static final ArgumentValidator PRE_CHECK = SqlRules.readOnlySql().validator();

    private SafeJdbc() {}

    /**
     * Runs one query in a read-only transaction, on PostgreSQL, MySQL or MariaDB.
     *
     * @param dataSource where to get a connection; it is closed afterwards
     * @param sql        the query, as the model wrote it
     * @param limits     timeout and result bounds
     * @return the rows, within the limits
     * @throws RefusedQueryException if the query fails the pre-check, the database is not one SafeJdbc can
     *                               make read-only, or a column has a type SafeJdbc does not return
     * @throws SQLException          if the database refuses or fails the query
     */
    public static Rows query(DataSource dataSource, String sql, Limits limits) throws SQLException {
        return run(dataSource, sql, limits, false);
    }

    /**
     * Runs one query on any database, for connections whose database role may only read. On
     * PostgreSQL, MySQL and MariaDB the transaction is made read-only as well; elsewhere the role is
     * the only thing that refuses writes.
     *
     * @param readOnlyDataSource where to get a connection as a role that may only read; it is closed afterwards
     * @param sql                the query, as the model wrote it
     * @param limits             timeout and result bounds
     * @return the rows, within the limits
     * @throws RefusedQueryException if the query fails the pre-check, or a column has a type SafeJdbc does not
     *                               return
     * @throws SQLException          if the database refuses or fails the query
     */
    public static Rows queryWithReadOnlyRole(DataSource readOnlyDataSource, String sql, Limits limits)
            throws SQLException {
        return run(readOnlyDataSource, sql, limits, true);
    }

    private static Rows run(DataSource dataSource, String sql, Limits limits, boolean readOnlyRole)
            throws SQLException {
        Optional<String> problem = PRE_CHECK.problem(ArgValue.text(sql));
        if (problem.isPresent()) {
            throw new RefusedQueryException(problem.get());
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            Engine engine = Engine.of(product);
            if (!engine.canEnforceReadOnly() && !readOnlyRole) {
                throw new RefusedQueryException("SafeJdbc cannot make a " + product + " transaction read-only; "
                        + "connect as a role that may only read and use queryWithReadOnlyRole");
            }
            return inReadOnlyTransaction(connection, engine, sql, limits);
        }
    }

    private static Rows inReadOnlyTransaction(Connection connection, Engine engine, String sql, Limits limits)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        boolean readOnly = connection.isReadOnly();
        SQLException failure = null;
        try {
            // A hint only, but drivers that honor it add their own read-only transaction; it must be set
            // before the transaction starts.
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            engine.startReadOnly(connection);
            return execute(connection, sql, limits);
        } catch (SQLException e) {
            failure = e;
            throw e;
        } finally {
            // Nothing a query did is kept, and the pooled connection goes back as it came.
            failure = attempt(connection::rollback, failure);
            failure = attempt(() -> connection.setAutoCommit(autoCommit), failure);
            failure = attempt(() -> connection.setReadOnly(readOnly), failure);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Runs a clean-up step, keeping the first failure and adding later ones to it. */
    private static SQLException attempt(SqlAction action, SQLException failure) {
        try {
            action.run();
            return failure;
        } catch (SQLException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }

    private static Rows execute(Connection connection, String sql, Limits limits) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(withoutTrailingSemicolon(sql))) {
            statement.setQueryTimeout(limits.timeoutSeconds());
            // One row more than returned, to tell whether rows were left out.
            int fetched = limits.maxRows() == Integer.MAX_VALUE ? limits.maxRows() : limits.maxRows() + 1;
            statement.setMaxRows(fetched);
            statement.setFetchSize(fetched);
            try (ResultSet result = statement.executeQuery()) {
                return read(result, limits);
            }
        }
    }

    /** The pre-check allows one trailing semicolon; some drivers reject it in a prepared statement. */
    private static String withoutTrailingSemicolon(String sql) {
        String body = sql.strip();
        return body.endsWith(";") ? body.substring(0, body.length() - 1).strip() : body;
    }

    private static Rows read(ResultSet result, Limits limits) throws SQLException {
        ResultSetMetaData metaData = result.getMetaData();
        int columnCount = metaData.getColumnCount();
        var columns = new ArrayList<String>(columnCount);
        var kinds = new ArrayList<ValueKind>(columnCount);
        for (int column = 1; column <= columnCount; column++) {
            String label = metaData.getColumnLabel(column);
            int type = metaData.getColumnType(column);
            ValueKind kind = ValueKind.of(type, metaData.getPrecision(column)).orElseThrow(() -> new RefusedQueryException("column " + label
                    + " has type " + typeName(type) + ", which SafeJdbc does not return; select it as text"
                    + " or leave it out"));
            columns.add(label);
            kinds.add(kind);
        }
        var rows = new ArrayList<List<ArgValue>>();
        boolean truncated = false;
        while (result.next()) {
            if (rows.size() == limits.maxRows()) {
                truncated = true;
                break;
            }
            var row = new ArrayList<ArgValue>(columnCount);
            for (int column = 1; column <= columnCount; column++) {
                Cell cell = kinds.get(column - 1).read(result, column, limits.maxCellLength());
                row.add(cell.value());
                truncated |= cell.cut();
            }
            rows.add(row);
        }
        return new Rows(columns, rows, truncated);
    }

    private static String typeName(int type) {
        try {
            return JDBCType.valueOf(type).getName();
        } catch (IllegalArgumentException e) {
            return "code " + type;
        }
    }

    record Cell(ArgValue value, boolean cut) {}

    /** How a column's values become argument values; anything not listed is refused. */
    enum ValueKind {
        BOOLEAN, NUMBER, TEXT, DATE, TIME, TIMESTAMP, TIME_WITH_ZONE, TIMESTAMP_WITH_ZONE;

        /** A {@code BIT} column wider than one bit, such as PostgreSQL's {@code bit(8)}, is a bit string, not a boolean. */
        static Optional<ValueKind> of(int type, int precision) {
            return Optional.ofNullable(switch (type) {
                case Types.BIT -> precision > 1 ? null : BOOLEAN;
                case Types.BOOLEAN -> BOOLEAN;
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.NUMERIC,
                     Types.REAL, Types.FLOAT, Types.DOUBLE -> NUMBER;
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                     Types.LONGNVARCHAR -> TEXT;
                case Types.DATE -> DATE;
                case Types.TIME -> TIME;
                case Types.TIMESTAMP -> TIMESTAMP;
                case Types.TIME_WITH_TIMEZONE -> TIME_WITH_ZONE;
                case Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMP_WITH_ZONE;
                default -> null;
            });
        }

        Cell read(ResultSet result, int column, int maxLength) throws SQLException {
            Object value = switch (this) {
                case BOOLEAN -> result.getBoolean(column);
                case NUMBER -> result.getObject(column);
                case TEXT -> result.getString(column);
                case DATE -> result.getObject(column, LocalDate.class);
                case TIME -> result.getObject(column, LocalTime.class);
                case TIMESTAMP -> result.getObject(column, LocalDateTime.class);
                case TIME_WITH_ZONE -> result.getObject(column, OffsetTime.class);
                case TIMESTAMP_WITH_ZONE -> result.getObject(column, OffsetDateTime.class);
            };
            if (value == null || result.wasNull()) {
                return new Cell(ArgValue.nullValue(), false);
            }
            return switch (value) {
                case Boolean flag -> new Cell(ArgValue.bool(flag), false);
                case BigDecimal decimal -> new Cell(ArgValue.number(decimal), false);
                case Double number when number.isNaN() || number.isInfinite() -> text(number.toString(), maxLength);
                case Float number when number.isNaN() || number.isInfinite() -> text(number.toString(), maxLength);
                case Double number -> new Cell(ArgValue.number(BigDecimal.valueOf(number)), false);
                case Float number -> new Cell(ArgValue.number(new BigDecimal(number.toString())), false);
                case Number number -> new Cell(ArgValue.number(new BigDecimal(number.toString())), false);
                default -> text(value.toString(), maxLength);
            };
        }

        private static Cell text(String text, int maxLength) {
            if (text.codePointCount(0, text.length()) <= maxLength) {
                return new Cell(ArgValue.text(text), false);
            }
            return new Cell(ArgValue.text(text.substring(0, text.offsetByCodePoints(0, maxLength))), true);
        }
    }
}
