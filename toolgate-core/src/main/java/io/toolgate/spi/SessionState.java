package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.Declassified;
import io.toolgate.spi.SessionEvent.ResultRecorded;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

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
 * @param taint     the source labels the session holds, each with the call that first brought it in
 */
public record SessionState(String sessionId, long version, int toolCalls, int denials, Map<Label, TaintSource> taint) {

    /** Rejects negative counters, which only a corrupt store could produce. */
    public SessionState {
        requireNonBlank(sessionId, "sessionId");
        if (version < 0 || toolCalls < 0 || denials < 0) {
            throw new IllegalArgumentException("session counters must not be negative");
        }
        taint = taint.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(taint));
    }

    /**
     * The state of a session that has no events yet.
     *
     * @param sessionId the session
     * @return the state at version zero
     */
    public static SessionState empty(String sessionId) {
        return new SessionState(sessionId, 0, 0, 0, Map.of());
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
            case CallDecided decided -> switch (decided.applied()) {
                // A denied call releases the budget it would have used, so probing costs denials, not calls.
                case Decision.Allow allowed -> next(toolCalls + 1, denials, taint);
                case Decision.Defer deferred -> next(toolCalls + 1, denials, taint);
                case Decision.Downgrade downgraded -> next(toolCalls + 1, denials, taint);
                case Decision.Deny denied -> next(toolCalls, denials + 1, taint);
                case Decision.Terminate terminated -> next(toolCalls, denials, taint);
            };
            // A withheld output counts as a denial, so probing through search results ends the run too.
            case ResultRecorded recorded -> recorded.withheld()
                    ? next(toolCalls, denials + 1, taint)
                    : next(toolCalls, denials, withLabels(recorded));
            case Declassified declassified -> next(toolCalls, denials, without(declassified.label()));
        };
    }

    /**
     * Whether the session holds a label.
     *
     * @param label a source label
     * @return {@code true} if some call brought it in and it was not declassified since
     */
    public boolean holds(Label label) {
        return taint.containsKey(label);
    }

    private SessionState next(int newToolCalls, int newDenials, Map<Label, TaintSource> newTaint) {
        return new SessionState(sessionId, version + 1, newToolCalls, newDenials, newTaint);
    }

    /** The first source of each label is kept: it is where the session became tainted. */
    private Map<Label, TaintSource> withLabels(ResultRecorded recorded) {
        var updated = new EnumMap<Label, TaintSource>(Label.class);
        updated.putAll(taint);
        recorded.labels().forEach(label -> updated.putIfAbsent(label, new TaintSource(recorded.callId(), recorded.tool())));
        return updated;
    }

    private Map<Label, TaintSource> without(Label label) {
        var updated = new EnumMap<Label, TaintSource>(Label.class);
        updated.putAll(taint);
        updated.remove(label);
        return updated;
    }
}
