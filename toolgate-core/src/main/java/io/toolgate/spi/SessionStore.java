package io.toolgate.spi;

import io.toolgate.session.InMemorySessionStore;
import java.util.List;

/**
 * Keeps each session's events and current state.
 *
 * <p>Appends are conditional on the version the caller read, so two parallel checks in one session
 * cannot both spend the last unit of a budget: the loser re-reads and decides again. Stores shared
 * across instances, such as Redis or JDBC, implement the same contract with their own
 * compare-and-set.
 */
public interface SessionStore {

    /**
     * A store in this JVM's memory, the gate's default: for one instance, or to share between gates in
     * tests, such as a gate redeployed with a new policy.
     *
     * @return a new, empty store
     */
    static SessionStore inMemory() {
        return new InMemorySessionStore();
    }

    /**
     * The current state of a session.
     *
     * @param sessionId the session
     * @return its state, or an empty state if the session has no events
     */
    SessionState state(String sessionId);

    /**
     * Appends an event if the session is still at {@code expectedVersion}.
     *
     * @param event           the event
     * @param expectedVersion the version the caller decided on
     * @return {@code true} if appended, {@code false} if another event was appended first
     */
    boolean append(SessionEvent event, long expectedVersion);

    /**
     * Appends an event whatever the session's version.
     *
     * <p>Used only for decisions no later event can invalidate, such as ending a run: counters only
     * grow, so a run that was over stays over. Skipping the version check keeps the calls of an
     * ended run from crowding out the ones still competing for budget. Session scopes that reset
     * counters must revisit this.
     *
     * @param event the event
     */
    void append(SessionEvent event);

    /**
     * Every event of a session, oldest first, for investigations such as a review screen's graph of what
     * the agent did. A store that keeps no event log returns nothing.
     *
     * @param sessionId the session
     * @return its events; empty for an unknown session, or when the store keeps none
     */
    default List<SessionEvent> events(String sessionId) {
        return List.of();
    }
}
