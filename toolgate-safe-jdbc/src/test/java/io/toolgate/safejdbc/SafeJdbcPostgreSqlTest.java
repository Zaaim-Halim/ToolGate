package io.toolgate.safejdbc;

import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;

/** SafeJdbc on a real PostgreSQL server. Runs under -Pcontainers. */
@Tag("containers")
class SafeJdbcPostgreSqlTest extends SafeJdbcServerTest {

    @Override
    ServerDatabase server() {
        return ServerDatabase.POSTGRESQL;
    }

    @Override
    void createFixtures(Statement admin) throws SQLException {
        admin.execute("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10, 2), note VARCHAR(200))");
        admin.execute("INSERT INTO orders VALUES (1, 480.00, 'first'), (2, 12.50, NULL), (3, 7, 'third')");
        // A sequence is outside transactions: nextval is never rolled back.
        admin.execute("CREATE SEQUENCE invoice_number");
    }

    @Override
    String sideEffect() {
        return "SELECT nextval('invoice_number') AS n";
    }

    @Override
    String sideEffectCount() {
        return "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM invoice_number";
    }

    @Override
    String readOnlyRefusal() {
        return "read-only transaction";
    }

    @Override
    UrlDataSource createReader(Statement admin, String user) throws SQLException {
        admin.execute("CREATE ROLE " + user + " LOGIN PASSWORD 'reader'");
        admin.execute("GRANT SELECT ON orders TO " + user);
        return new UrlDataSource(this.admin.url(), user, "reader");
    }

    @Override
    String slowQuery() {
        return "SELECT pg_sleep(10) AS slept";
    }
}
