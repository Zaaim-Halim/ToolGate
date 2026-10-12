package io.toolgate.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Creates ToolGate's tables and views from a dialect's DDL, as an application's migration would. */
final class Schema {

    private Schema() {}

    static void create(DataSource database, JdbcApprovalQueue.Dialect dialect) {
        try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
            for (String ddl : dialect.ddl().split(";")) {
                String sql = ddl.lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
