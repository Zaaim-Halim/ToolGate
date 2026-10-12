package io.toolgate.safejdbc;

import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;

/** SafeJdbc on a real MySQL server. Runs under -Pcontainers. */
@Tag("containers")
class SafeJdbcMySqlTest extends SafeJdbcServerTest {

    @Override
    ServerDatabase server() {
        return ServerDatabase.MYSQL;
    }

    @Override
    void createFixtures(Statement admin) throws SQLException {
        admin.execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10, 2), note VARCHAR(200))");
        admin.execute("INSERT INTO orders VALUES (1, 480.00, 'first'), (2, 12.50, NULL), (3, 7, 'third')");
        // MyISAM has no transactions: a row written to it is never rolled back.
        admin.execute("CREATE TABLE visits (n INT) ENGINE = MyISAM");
        admin.execute("""
                CREATE FUNCTION visit() RETURNS INT MODIFIES SQL DATA NOT DETERMINISTIC
                BEGIN
                    INSERT INTO visits VALUES (1);
                    RETURN 1;
                END""");
    }

    @Override
    String sideEffect() {
        return "SELECT visit() AS n";
    }

    @Override
    String sideEffectCount() {
        return "SELECT COUNT(*) FROM visits";
    }

    @Override
    String readOnlyRefusal() {
        return "READ ONLY transaction";
    }

    @Override
    UrlDataSource createReader(Statement admin, String user) throws SQLException {
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY 'reader'");
        admin.execute("GRANT SELECT ON " + database + ".orders TO '" + user + "'@'%'");
        return new UrlDataSource(this.admin.url(), user, "reader");
    }

    @Override
    String slowQuery() {
        return "SELECT SLEEP(10) AS slept";
    }
}
