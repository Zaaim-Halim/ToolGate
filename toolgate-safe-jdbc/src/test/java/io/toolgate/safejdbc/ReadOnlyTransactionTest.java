package io.toolgate.safejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * The statements SafeJdbc sends to the engines it makes read-only, against a recording fake. That the
 * real databases then refuse writes is tested in {@link SafeJdbcPostgreSqlTest} and {@link SafeJdbcMySqlTest}.
 */
class ReadOnlyTransactionTest {

    private static final String QUERY = "SELECT id FROM orders";

    @ParameterizedTest
    @CsvSource({
            "PostgreSQL, SET TRANSACTION READ ONLY",
            "MySQL,      START TRANSACTION READ ONLY",
            "MariaDB,    START TRANSACTION READ ONLY"})
    void startsAReadOnlyTransactionBeforeTheQueryAndRollsBack(String product, String readOnly) throws SQLException {
        var database = new RecordingDatabase(product);

        SafeJdbc.query(database.dataSource(), QUERY, Limits.defaults());

        assertThat(database.log).containsExactly(
                "setReadOnly true",
                "setAutoCommit false",
                "execute " + readOnly,
                "prepare " + QUERY,
                "setQueryTimeout 5",
                "setMaxRows 101",
                "executeQuery",
                "rollback",
                "setAutoCommit true",
                "setReadOnly false",
                "close");
    }

    @Test
    void preparesTheQueryWithoutItsTrailingSemicolon() throws SQLException {
        var database = new RecordingDatabase("PostgreSQL");

        SafeJdbc.query(database.dataSource(), QUERY + " ; ", Limits.defaults());

        assertThat(database.log).contains("prepare " + QUERY);
    }

    @Test
    void doesNotRunTheQueryWhenTheTransactionCannotBeMadeReadOnly() {
        var database = new RecordingDatabase("PostgreSQL", Optional.of("SET TRANSACTION READ ONLY"));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> SafeJdbc.query(database.dataSource(), QUERY, Limits.defaults()))
                .withMessage("refused by the test");
        assertThat(database.log).doesNotContain("prepare " + QUERY).containsSubsequence(
                "execute SET TRANSACTION READ ONLY", "rollback", "setAutoCommit true", "setReadOnly false", "close");
    }

    @Test
    void refusesOtherEnginesWithoutTouchingTheConnectionState() {
        var database = new RecordingDatabase("Microsoft SQL Server");

        assertThatExceptionOfType(RefusedQueryException.class)
                .isThrownBy(() -> SafeJdbc.query(database.dataSource(), QUERY, Limits.defaults()))
                .withMessageContaining("Microsoft SQL Server")
                .withMessageContaining("queryWithReadOnlyRole");
        assertThat(database.log).containsExactly("close");
    }

    @Test
    void aReadOnlyRoleStillGetsTheReadOnlyTransactionWhereOneExists() throws SQLException {
        var database = new RecordingDatabase("PostgreSQL");

        SafeJdbc.queryWithReadOnlyRole(database.dataSource(), QUERY, Limits.defaults());

        assertThat(database.log).contains("execute SET TRANSACTION READ ONLY");
    }

    @Test
    void otherEnginesWithARoleGetOnlyTheRollback() throws SQLException {
        var database = new RecordingDatabase("Microsoft SQL Server");

        SafeJdbc.queryWithReadOnlyRole(database.dataSource(), QUERY, Limits.defaults());

        assertThat(database.log).noneMatch(entry -> entry.startsWith("execute ")).contains("executeQuery", "rollback");
    }

    @Test
    void thePreCheckRunsBeforeAnyConnection() {
        var database = new RecordingDatabase("PostgreSQL");

        assertThatExceptionOfType(RefusedQueryException.class)
                .isThrownBy(() -> SafeJdbc.query(database.dataSource(),
                        "WITH t AS (DELETE FROM orders RETURNING *) SELECT * FROM t", Limits.defaults()))
                .withMessageContaining("DELETE");
        assertThat(database.log).isEmpty();
    }
}
