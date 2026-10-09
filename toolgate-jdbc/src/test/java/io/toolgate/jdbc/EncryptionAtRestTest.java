package io.toolgate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.ValueCodec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What lands in the database when the stores are given a key, and what an attacker with SQL access can do. */
class EncryptionAtRestTest {

    private static final Instant T0 = Instant.parse("2026-10-09T08:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] NEW_KEY = "fedcba9876543210fedcba9876543210".getBytes();
    private static final String SECRET = "billing@mycompany.com";

    private final H2 database = H2.withQueueTable();
    private final ValueCodec codec = ValueCodec.aesGcm("2026-10", KEY);

    @Test
    void queuedArgumentsAndReasonsAreNotReadableInTheDatabase() throws SQLException {
        var queue = new JdbcApprovalQueue(database, codec);
        PendingCall original = pending("refunds:c2", "480 is above 200 for " + SECRET);

        queue.add(original);

        assertThat(rawColumn("SELECT payload FROM toolgate_pending_call")).allSatisfy(payload -> assertThat(payload)
                .startsWith("tg1:2026-10:").doesNotContain(SECRET).doesNotContain("480"));
        assertThat(queue.get("refunds:c2")).contains(original);
    }

    /** With SQL access, an attacker copies an already-approved payload onto another pending call. */
    @Test
    void aPayloadCopiedToAnotherRowDoesNotOpen() throws SQLException {
        var queue = new JdbcApprovalQueue(database, codec);
        queue.add(pending("refunds:c2", "harmless"));
        queue.add(pending("refunds:c3", "harmless"));
        execute("UPDATE toolgate_pending_call SET payload = (SELECT payload FROM toolgate_pending_call"
                + " WHERE approval_id = 'refunds:c2') WHERE approval_id = 'refunds:c3'");

        assertThatThrownBy(() -> queue.get("refunds:c3")).isInstanceOf(JdbcStoreException.class);
        assertThat(queue.get("refunds:c2")).isPresent();
    }

    @Test
    void theWrongKeyOrNoKeyFailsLoudly() {
        new JdbcApprovalQueue(database, codec).add(pending("refunds:c2", "r"));

        assertThatThrownBy(() -> new JdbcApprovalQueue(database, ValueCodec.aesGcm("2026-10", NEW_KEY)).get("refunds:c2"))
                .isInstanceOf(JdbcStoreException.class);
        assertThatThrownBy(() -> new JdbcApprovalQueue(database, ValueCodec.unencrypted()).get("refunds:c2"))
                .isInstanceOf(JdbcStoreException.class);
    }

    @Test
    void loggedEventsAreSealedAndBoundToTheirPlaceInTheLog() throws SQLException {
        var store = new JdbcSessionStore(database, codec);
        SessionEvent first = decided("s1/1");
        SessionEvent second = decided("s1/2");
        store.append(first);
        store.append(second);

        assertThat(rawColumn("SELECT event FROM toolgate_session_event")).allSatisfy(event -> assertThat(event)
                .startsWith("tg1:").doesNotContain(SECRET));
        assertThat(store.events("s1")).containsExactly(first, second);

        execute("UPDATE toolgate_session_event SET version = 3 WHERE version = 1");
        execute("UPDATE toolgate_session_event SET version = 1 WHERE version = 2");
        execute("UPDATE toolgate_session_event SET version = 2 WHERE version = 3");
        assertThatThrownBy(() -> store.events("s1")).isInstanceOf(JdbcStoreException.class);
    }

    @Test
    void rotationKeepsOldRowsReadable() {
        new JdbcSessionStore(database, ValueCodec.aesGcm("2026-07", KEY)).append(decided("s1/1"));
        var rotated = new JdbcSessionStore(database, ValueCodec.aesGcm("2026-10", NEW_KEY).alsoOpening("2026-07", KEY));

        rotated.append(decided("s1/2"));

        assertThat(rotated.events("s1")).hasSize(2);
    }

    private static PendingCall pending(String approvalId, String reason) {
        var call = new ToolCall(approvalId.substring(8), Principal.service("refund-agent", Tenant.of("acme")),
                "refund-agent", "refunds", ToolId.local("sendRefundEmail"),
                ObjectValue.of(Map.of("to", ArgValue.text(SECRET), "amount", ArgValue.number(480))), T0);
        return new PendingCall(approvalId, call, new Decision.Defer("sendRefundEmail.amount", reason, approvalId), T0,
                T0.plus(Duration.ofHours(24)), ApprovalStatus.PENDING);
    }

    private static SessionEvent decided(String callId) {
        ObjectValue args = ObjectValue.of(Map.of("to", ArgValue.text(SECRET)));
        return new SessionEvent.CallDecided("s1", callId, "agent", Principal.user("maria", Tenant.of("acme")),
                ToolId.local("sendEmail"), args, new Decision.Allow("allow-list"), new Decision.Allow("allow-list"),
                "sha256:abc", T0);
    }

    private List<String> rawColumn(String sql) throws SQLException {
        try (Connection connection = database.getConnection(); var rows = connection.createStatement().executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
