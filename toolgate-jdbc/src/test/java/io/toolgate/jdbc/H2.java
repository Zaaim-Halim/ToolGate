package io.toolgate.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A fresh in-memory H2 database with the queue table, reached through DriverManager so the tests
 * need no H2 classes at compile time.
 */
final class H2 implements DataSource {

    private final String url = "jdbc:h2:mem:toolgate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    static H2 withQueueTable() {
        var database = new H2();
        try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
            for (String ddl : JdbcApprovalQueue.Dialect.H2.ddl().split(";")) {
                String sql = ddl.lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return database;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) { }

    @Override
    public void setLoginTimeout(int seconds) { }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
