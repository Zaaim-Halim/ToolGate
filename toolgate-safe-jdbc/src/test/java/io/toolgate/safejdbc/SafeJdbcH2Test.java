package io.toolgate.safejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.toolgate.api.ArgValue;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SafeJdbc on H2, which it cannot make read-only: refused by default, and real reads through a role. */
class SafeJdbcH2Test {

    private UrlDataSource dataSource;
    private Connection keepAlive;

    @BeforeEach
    void createOrders() throws SQLException {
        dataSource = new UrlDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "", "");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("""
                    CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10, 2), paid BOOLEAN, note VARCHAR(200),
                                         due DATE, created TIMESTAMP, receipt BLOB)""");
            statement.execute("""
                    INSERT INTO orders VALUES
                      (1, 480.00, TRUE, 'first', DATE '2026-10-01', TIMESTAMP '2026-10-01 09:30:00', NULL),
                      (2, 12.50, FALSE, NULL, NULL, NULL, NULL),
                      (3, 7, FALSE, 'third', NULL, NULL, NULL)""");
        }
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    @Test
    void refusesH2WithoutAReadOnlyRole() {
        assertThatExceptionOfType(RefusedQueryException.class)
                .isThrownBy(() -> SafeJdbc.query(dataSource, "SELECT id FROM orders", Limits.defaults()))
                .withMessage("SafeJdbc cannot make a H2 transaction read-only; connect as a role that may only read"
                        + " and use queryWithReadOnlyRole");
    }

    @Test
    void returnsTypedValues() throws SQLException {
        Rows rows = SafeJdbc.queryWithReadOnlyRole(dataSource,
                "SELECT id, total, paid, note, due, created FROM orders ORDER BY id", Limits.defaults());

        assertThat(rows.columns()).containsExactly("ID", "TOTAL", "PAID", "NOTE", "DUE", "CREATED");
        assertThat(rows.truncated()).isFalse();
        assertThat(rows.rows()).hasSize(3);
        assertThat(rows.rows().getFirst()).containsExactly(ArgValue.number(1), ArgValue.number(new BigDecimal("480")),
                ArgValue.bool(true), ArgValue.text("first"), ArgValue.text("2026-10-01"),
                ArgValue.text("2026-10-01T09:30"));
        assertThat(rows.rows().get(1)).containsExactly(ArgValue.number(2), ArgValue.number(new BigDecimal("12.5")),
                ArgValue.bool(false), ArgValue.nullValue(), ArgValue.nullValue(), ArgValue.nullValue());
    }

    @Test
    void leavesOutRowsPastTheLimit() throws SQLException {
        Rows capped = SafeJdbc.queryWithReadOnlyRole(dataSource, "SELECT id FROM orders ORDER BY id",
                Limits.defaults().maxRows(2));
        Rows exact = SafeJdbc.queryWithReadOnlyRole(dataSource, "SELECT id FROM orders ORDER BY id",
                Limits.defaults().maxRows(3));

        assertThat(capped.rows()).containsExactly(List.of(ArgValue.number(1)), List.of(ArgValue.number(2)));
        assertThat(capped.truncated()).isTrue();
        assertThat(exact.rows()).hasSize(3);
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void cutsLongText() throws SQLException {
        Rows rows = SafeJdbc.queryWithReadOnlyRole(dataSource, "SELECT note FROM orders WHERE id = 1",
                Limits.defaults().maxCellLength(3));

        assertThat(rows.rows()).containsExactly(List.of(ArgValue.text("fir")));
        assertThat(rows.truncated()).isTrue();
    }

    @Test
    void refusesBinaryColumns() {
        assertThatExceptionOfType(RefusedQueryException.class)
                .isThrownBy(() -> SafeJdbc.queryWithReadOnlyRole(dataSource, "SELECT id, receipt FROM orders",
                        Limits.defaults()))
                .withMessage("column RECEIPT has type BLOB, which SafeJdbc does not return; select it as text or"
                        + " leave it out");
    }

    @Test
    void refusesWritesBeforeTheyReachTheDatabase() throws SQLException {
        assertThatExceptionOfType(RefusedQueryException.class)
                .isThrownBy(() -> SafeJdbc.queryWithReadOnlyRole(dataSource, "DELETE FROM orders", Limits.defaults()))
                .withMessage("SQL contains DELETE, which writes, locks or calls a procedure");
        assertThat(SafeJdbc.queryWithReadOnlyRole(dataSource, "SELECT count(*) AS n FROM orders", Limits.defaults())
                .rows()).containsExactly(List.of(ArgValue.number(3)));
    }

    @Test
    void theRoleIsWhatRefusesOnH2(@TempDir Path folder) throws SQLException {
        // FILE_WRITE writes a file from inside a SELECT: no pre-check can catch a function with side effects,
        // and H2 has no read-only transaction, so only the role stands in the way.
        Path written = folder.resolve("out.txt");
        String sideEffect = "SELECT FILE_WRITE('leaked', '" + written + "') AS n";
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE USER reader PASSWORD 'reader'");
            statement.execute("GRANT SELECT ON orders TO reader");
        }
        var reader = new UrlDataSource(dataSource.url(), "reader", "reader");

        assertThat(SafeJdbc.queryWithReadOnlyRole(reader, "SELECT id FROM orders WHERE id = 3", Limits.defaults())
                .rows()).containsExactly(List.of(ArgValue.number(3)));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> SafeJdbc.queryWithReadOnlyRole(reader, sideEffect, Limits.defaults()))
                .isNotInstanceOf(RefusedQueryException.class)
                .withMessageContaining("Admin rights are required");
        assertThat(written).doesNotExist();

        SafeJdbc.queryWithReadOnlyRole(dataSource, sideEffect, Limits.defaults());
        assertThat(written).exists();
    }
}
