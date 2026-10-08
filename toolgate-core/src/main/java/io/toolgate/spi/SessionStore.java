package io.toolgate.spi;

/**
 * Keeps each session's events and current state.
 *
 * <p>Appends are conditional on the version the caller read, so two parallel checks in one session
 * cannot both spend the last unit of a budget: the loser re-reads and decides again. Stores shared
 * across instances, such as Redis or JDBC, implement the same contract with their own
 * compare-and-set.
 */
public interface SessionStore {

    /** The current state of a session; an empty state if the session has no events. */
    SessionState state(String sessionId);

    /**
     * Appends an event if the session is still at {@code expectedVersion}.
     *
     * @return {@code true} if appended, {@code false} if another event was appended first
     */
    boolean append(SessionEvent event, long expectedVersion);

    /**
     * Appends an event whatever the session's version.
     *
     * <p>Used only for decisions no later event can invalidate, such as ending a run: counters only
     * grow, so a run that was over stays over. Skipping the version check keeps the calls of an
     * ended run from crowding out the ones still competing for budget.
     */
    void append(SessionEvent event);
}
