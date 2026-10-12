package io.toolgate.jdbc;

import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/** The JDBC stores' tests on a real MySQL server, from its own DDL. Runs under -Pcontainers. */
@Tag("containers")
class MySqlTest {

    private static final ServerDatabase SERVER = ServerDatabase.MYSQL;

    @Nested
    class ApprovalQueue extends JdbcApprovalQueueTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }

        @Override
        DataSource emptyDatabase() {
            return SERVER.empty();
        }
    }

    @Nested
    class AuditSink extends JdbcAuditSinkTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }

        @Override
        DataSource emptyDatabase() {
            return SERVER.empty();
        }
    }

    @Nested
    class SessionStore extends JdbcSessionStoreTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }
    }

    @Nested
    class EncryptionAtRest extends EncryptionAtRestTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }
    }

    @Nested
    class Queries extends ToolGateQueriesTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }
    }

    @Nested
    class TwoInstances extends TwoInstancesTest {
        @Override
        DataSource newDatabase() {
            return SERVER.withQueueTable();
        }
    }
}
