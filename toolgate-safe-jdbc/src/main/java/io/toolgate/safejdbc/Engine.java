package io.toolgate.safejdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * How each database is told that a transaction may only read. {@link Connection#setReadOnly} is
 * not enough: JDBC calls it a hint, H2 ignores it (an INSERT succeeds, H2 2.5.250), and the
 * PostgreSQL driver applies it only with autocommit off, or never with {@code readOnlyMode=ignore}.
 * So SafeJdbc sends each database's own statement, and knows no statement for any other database.
 */
enum Engine {

    /** {@code SET TRANSACTION} must come first in the transaction, so it runs before the query. */
    POSTGRESQL("SET TRANSACTION READ ONLY"),
    MYSQL("START TRANSACTION READ ONLY"),
    MARIADB("START TRANSACTION READ ONLY"),
    OTHER(null);

    private final String readOnlyStatement;

    Engine(String readOnlyStatement) {
        this.readOnlyStatement = readOnlyStatement;
    }

    static Engine of(String productName) {
        return switch (productName.strip().toLowerCase(Locale.ROOT)) {
            case "postgresql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            default -> OTHER;
        };
    }

    boolean canEnforceReadOnly() {
        return readOnlyStatement != null;
    }

    /** Starts a read-only transaction on a connection with autocommit off; does nothing on other engines. */
    void startReadOnly(Connection connection) throws SQLException {
        if (readOnlyStatement != null) {
            try (Statement start = connection.createStatement()) {
                start.execute(readOnlyStatement);
            }
        }
    }
}
