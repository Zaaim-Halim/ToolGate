package io.toolgate.safejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.toolgate.api.ArgValue;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SafeJdbc on a real server: the database itself refuses a write the pre-check cannot see, a write
 * that a rollback would not undo, so only the read-only transaction or the role stands in the way.
 */
abstract class SafeJdbcServerTest {

    UrlDataSource admin;
    String database;

    abstract ServerDatabase server();

    /** Creates {@code orders}, with three rows, and the objects {@link #sideEffect()} needs. */
    abstract void createFixtures(Statement admin) throws SQLException;

    /** A query that passes the pre-check but writes something a rollback does not undo. */
    abstract String sideEffect();

    /** How many times {@link #sideEffect()} has written. */
    abstract String sideEffectCount();

    /** What the database says when a read-only transaction refuses the write. */
    abstract String readOnlyRefusal();

    /** Creates a user that may only read {@code orders}, and returns a data source for it. */
    abstract UrlDataSource createReader(Statement admin, String user) throws SQLException;

    /** A query that takes ten seconds. */
    abstract String slowQuery();

    @BeforeEach
    void createDatabase() throws SQLException {
        var fresh = server().fresh();
        admin = fresh.admin();
        database = fresh.name();
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            createFixtures(statement);
        }
    }

    @Test
    void readsRows() throws SQLException {
        Rows rows = SafeJdbc.query(admin, "SELECT id, total, note FROM orders ORDER BY id", Limits.defaults());

        assertThat(rows.rows()).containsExactly(
                List.of(ArgValue.number(1), ArgValue.number(new BigDecimal("480")), ArgValue.text("first")),
                List.of(ArgValue.number(2), ArgValue.number(new BigDecimal("12.5")), ArgValue.nullValue()),
                List.of(ArgValue.number(3), ArgValue.number(new BigDecimal("7")), ArgValue.text("third")));
        assertThat(rows.truncated()).isFalse();
    }

    @Test
    void theReadOnlyTransactionRefusesAWriteThePreCheckCannotSee() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> SafeJdbc.query(admin, sideEffect(), Limits.defaults()))
                .isNotInstanceOf(RefusedQueryException.class)
                .withMessageContaining(readOnlyRefusal());

        assertThat(count()).isZero();
    }

    @Test
    void withoutSafeJdbcTheSameQueryWrites() throws SQLException {
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery(sideEffect()).close();
        }

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void leavesTheConnectionAsItCame() throws SQLException {
        try (Connection connection = admin.getConnection()) {
            var single = new SingleConnection(connection);

            SafeJdbc.query(single, "SELECT id FROM orders", Limits.defaults());

            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.isReadOnly()).isFalse();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE orders SET note = 'still writable' WHERE id = 2");
            }
        }
    }

    @Test
    void aReadOnlyRoleReadsAndCannotWrite() throws SQLException {
        UrlDataSource reader;
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            reader = createReader(statement, "reader_" + UUID.randomUUID().toString().substring(0, 8));
        }

        assertThat(SafeJdbc.queryWithReadOnlyRole(reader, "SELECT id FROM orders WHERE id = 3", Limits.defaults())
                .rows()).containsExactly(List.of(ArgValue.number(3)));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> SafeJdbc.queryWithReadOnlyRole(reader, sideEffect(), Limits.defaults()))
                .isNotInstanceOf(RefusedQueryException.class);
        assertThat(count()).isZero();
    }

    @Test
    void stopsASlowQuery() {
        long started = System.nanoTime();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> SafeJdbc.query(admin, slowQuery(), Limits.defaults().timeout(Duration.ofSeconds(1))))
                .isNotInstanceOf(RefusedQueryException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    private long count() throws SQLException {
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sideEffectCount())) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
