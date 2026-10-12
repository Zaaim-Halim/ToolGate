package io.toolgate.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A fresh database on a real PostgreSQL or MySQL server. Each server starts once per test run, in a
 * container, and is stopped when the JVM exits; each test gets a database of its own on it.
 */
enum ServerDatabase {

    POSTGRESQL(JdbcApprovalQueue.Dialect.POSTGRESQL) {
        @Override
        JdbcDatabaseContainer<?> container() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Override
        String url(JdbcDatabaseContainer<?> server, String database) {
            return "jdbc:postgresql://" + server.getHost() + ":" + server.getMappedPort(5432) + "/" + database;
        }
    },

    MYSQL(JdbcApprovalQueue.Dialect.MYSQL) {
        @Override
        JdbcDatabaseContainer<?> container() {
            return new MySQLContainer("mysql:9.6.0").withUsername("root");
        }

        @Override
        String url(JdbcDatabaseContainer<?> server, String database) {
            return "jdbc:mysql://" + server.getHost() + ":" + server.getMappedPort(3306) + "/" + database;
        }
    };

    private final JdbcApprovalQueue.Dialect dialect;
    private JdbcDatabaseContainer<?> server;

    ServerDatabase(JdbcApprovalQueue.Dialect dialect) {
        this.dialect = dialect;
    }

    abstract JdbcDatabaseContainer<?> container();

    abstract String url(JdbcDatabaseContainer<?> server, String database);

    /**
     * A new, empty database.
     *
     * @return the database
     */
    synchronized DataSource empty() {
        if (server == null) {
            server = container();
            server.start();
            JdbcDatabaseContainer<?> started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
        }
        String name = "toolgate_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = server.createConnection(""); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return new Url(url(server, name), server.getUsername(), server.getPassword());
    }

    /**
     * A new database with ToolGate's tables, created from this server's DDL.
     *
     * @return the database
     */
    DataSource withQueueTable() {
        DataSource database = empty();
        Schema.create(database, dialect);
        return database;
    }

    /** A data source over {@link DriverManager}. */
    private record Url(String url, String user, String password) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String otherUser, String otherPassword) throws SQLException {
            return DriverManager.getConnection(url, otherUser, otherPassword);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

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
}
