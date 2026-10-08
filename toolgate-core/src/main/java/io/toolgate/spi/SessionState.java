package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.spi.SessionEvent.CallDecided;

/**
 * An immutable snapshot of one session, built by folding its events in order.
 *
 * <p>Stores keep events or snapshots as they prefer, but must produce exactly what folding the
 * events with {@link #apply} produces, so that every store makes the same decisions.
 *
 * @param sessionId the session
 * @param version   the number of events applied; stores use it for optimistic concurrency
 * @param toolCalls calls that used tool-call budget: allowed, deferred or downgraded ones
 * @param denials   calls that were denied, for the denial budget
 */
public record SessionState(String sessionId, long version, int toolCalls, int denials) {

    /** Rejects negative counters, which only a corrupt store could produce. */
    public SessionState {
        requireNonBlank(sessionId, "sessionId");
        if (version < 0 || toolCalls < 0 || denials < 0) {
            throw new IllegalArgumentException("session counters must not be negative");
        }
    }

    /**
     * The state of a session that has no events yet.
     *
     * @param sessionId the session
     * @return the state at version zero
     */
    public static SessionState empty(String sessionId) {
        return new SessionState(sessionId, 0, 0, 0);
    }

    /**
     * The state after one more event.
     *
     * @param event an event of this session
     * @return the new state, one version later
     */
    public SessionState apply(SessionEvent event) {
        if (!event.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException(
                    "event for session " + event.sessionId() + " applied to session " + sessionId);
        }
        return switch (event) {
            case CallDecided decided -> switch (decided.outcome()) {
                // A denied call releases the budget it would have used, so probing costs denials, not calls.
                case ALLOWED, DEFERRED, DOWNGRADED -> new SessionState(sessionId, version + 1, toolCalls + 1, denials);
                case DENIED -> new SessionState(sessionId, version + 1, toolCalls, denials + 1);
                case TERMINATED -> new SessionState(sessionId, version + 1, toolCalls, denials);
            };
        };
    }
}
