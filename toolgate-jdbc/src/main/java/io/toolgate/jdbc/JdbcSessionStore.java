package io.toolgate.jdbc;

import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Session state in the application's database, so every instance of the application decides on
 * the same taint and budgets. Needed whenever calls of one session can reach different instances,
 * and whenever deferred calls may be approved on another instance than the one that deferred them.
 *
 * <p>Each append is one transaction: read the session's snapshot, check its version, write the new
 * snapshot, and insert the event into the log. Two instances racing on one session cannot both
 * commit, so budgets hold across instances.
 *
 * <p>The event log keeps every session event, including the calls' real argument values,
 * unencrypted for now: protect the table like the data it carries, and bound how long it is kept
 * with {@link #purgeIdle}. Every check costs a database round trip, which is the price of
 * consistent decisions across instances; the in-memory store remains the fast choice for one
 * instance.
 */
public final class JdbcSessionStore implements SessionStore {

    /** Attempts at an append that must succeed, against other instances appending meanwhile. */
    private static final int MAX_UNCONDITIONAL_ATTEMPTS = 100;

    private final DataSource dataSource;
    private final Clock clock;

    /**
     * A store in the tables the shipped DDL creates, timing writes with the system clock.
     *
     * @param dataSource where the tables are
     */
    public JdbcSessionStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /**
     * A store in the tables the shipped DDL creates.
     *
     * @param dataSource where the tables are
     * @param clock      times writes, for {@link #purgeIdle}
     */
    public JdbcSessionStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SessionState state(String sessionId) {
        try (Connection connection = dataSource.getConnection()) {
            return read(connection, sessionId).orElseGet(() -> SessionState.empty(sessionId));
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read session " + sessionId, failure);
        }
    }

    @Override
    public boolean append(SessionEvent event, long expectedVersion) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean appended = appendIn(connection, event, expectedVersion);
                if (appended) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return appended;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            if (JdbcApprovalQueue.isIntegrityViolation(failure) || isTransactionRollback(failure)) {
                // Another instance inserted this session or this event's version first, or the database
                // rolled this transaction back to resolve a race (serialization failure, deadlock): either
                // way another writer won, and the caller decides again on the new state.
                return false;
            }
            throw new JdbcStoreException("could not append to session " + event.sessionId(), failure);
        }
    }

    @Override
    public void append(SessionEvent event) {
        for (int attempt = 0; attempt < MAX_UNCONDITIONAL_ATTEMPTS; attempt++) {
            if (append(event, state(event.sessionId()).version())) {
                return;
            }
        }
        throw new JdbcStoreException("session " + event.sessionId() + " changed on each of "
                + MAX_UNCONDITIONAL_ATTEMPTS + " attempts", null);
    }

    /**
     * Every event of a session, in order: the log replay and investigations read.
     *
     * @param sessionId the session
     * @return its events, oldest first; empty for an unknown or purged session
     */
    public List<SessionEvent> events(String sessionId) {
        String sql = "SELECT event FROM toolgate_session_event WHERE session_id = ? ORDER BY version";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, sessionId);
            try (ResultSet rows = select.executeQuery()) {
                var events = new ArrayList<SessionEvent>();
                while (rows.next()) {
                    events.add(Codec.event(rows.getString("event")));
                }
                return events;
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read the events of session " + sessionId, failure);
        } catch (IllegalArgumentException corrupt) {
            throw new JdbcStoreException("unreadable event in session " + sessionId, corrupt);
        }
    }

    /**
     * Deletes sessions, and their events, not written to for {@code idle}. Run it on a schedule. A
     * purged session that comes back starts clean, with no taint and fresh budgets, so choose a
     * period longer than any run or conversation that must stay bounded.
     *
     * @param idle how long a session must have been untouched
     * @return how many sessions were deleted
     */
    public int purgeIdle(Duration idle) {
        long cutoff = Codec.micros(clock.instant().minus(idle));
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement events = connection.prepareStatement("DELETE FROM toolgate_session_event"
                         + " WHERE session_id IN (SELECT session_id FROM toolgate_session WHERE updated_at < ?)");
                 PreparedStatement sessions = connection.prepareStatement(
                         "DELETE FROM toolgate_session WHERE updated_at < ?")) {
                events.setLong(1, cutoff);
                events.executeUpdate();
                sessions.setLong(1, cutoff);
                int purged = sessions.executeUpdate();
                connection.commit();
                return purged;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not purge idle sessions", failure);
        }
    }

    private boolean appendIn(Connection connection, SessionEvent event, long expectedVersion) throws SQLException {
        String sessionId = event.sessionId();
        Optional<SessionState> stored = read(connection, sessionId);
        SessionState current = stored.orElseGet(() -> SessionState.empty(sessionId));
        if (current.version() != expectedVersion) {
            return false;
        }
        SessionState next = current.apply(event);
        long now = Codec.micros(clock.instant());
        if (stored.isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO toolgate_session"
                    + " (session_id, version, tool_calls, denials, taint, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, sessionId);
                insert.setLong(2, next.version());
                insert.setInt(3, next.toolCalls());
                insert.setInt(4, next.denials());
                insert.setString(5, Codec.taint(next.taint()));
                insert.setLong(6, now);
                insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update = connection.prepareStatement("UPDATE toolgate_session"
                    + " SET version = ?, tool_calls = ?, denials = ?, taint = ?, updated_at = ?"
                    + " WHERE session_id = ? AND version = ?")) {
                update.setLong(1, next.version());
                update.setInt(2, next.toolCalls());
                update.setInt(3, next.denials());
                update.setString(4, Codec.taint(next.taint()));
                update.setLong(5, now);
                update.setString(6, sessionId);
                update.setLong(7, expectedVersion);
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
        }
        try (PreparedStatement log = connection.prepareStatement("INSERT INTO toolgate_session_event"
                + " (session_id, version, event_type, event, recorded_at) VALUES (?, ?, ?, ?, ?)")) {
            log.setString(1, sessionId);
            log.setLong(2, next.version());
            log.setString(3, event.getClass().getSimpleName());
            log.setString(4, Codec.event(event));
            log.setLong(5, now);
            log.executeUpdate();
        }
        return true;
    }

    /** SQL state class 40: serialization failure or deadlock, the database's way to end a race. */
    private static boolean isTransactionRollback(SQLException failure) {
        return failure.getSQLState() != null && failure.getSQLState().startsWith("40");
    }

    private static Optional<SessionState> read(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT version, tool_calls, denials, taint FROM toolgate_session WHERE session_id = ?")) {
            select.setString(1, sessionId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(new SessionState(sessionId, row.getLong("version"), row.getInt("tool_calls"),
                            row.getInt("denials"), Codec.taint(row.getString("taint"))));
                } catch (IllegalArgumentException corrupt) {
                    throw new JdbcStoreException("unreadable state of session " + sessionId, corrupt);
                }
            }
        }
    }
}
