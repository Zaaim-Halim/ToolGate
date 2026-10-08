package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.time.Instant;
import java.util.Objects;

/**
 * Something that happened in a session. A session's state is the fold of its events, so it can
 * always be rebuilt, and later replayed against another policy.
 *
 * <p>Sealed because stores persist these events: they must know every kind they may meet.
 */
public sealed interface SessionEvent permits SessionEvent.CallDecided {

    /**
     * The session this event belongs to.
     *
     * @return the session's ID
     */
    String sessionId();

    /**
     * A call was checked and this decision applied.
     *
     * @param sessionId the session
     * @param callId    the call
     * @param tool      the tool the model asked for
     * @param outcome   the decision that was applied
     * @param at        when the call was captured
     */
    record CallDecided(String sessionId, String callId, ToolId tool, Outcome outcome, Instant at)
            implements SessionEvent {

        /** The kinds of decision, as recorded in the session. */
        public enum Outcome {
            /** The tool ran. */
            ALLOWED,
            /** The tool did not run. */
            DENIED,
            /** The call went to review. */
            DEFERRED,
            /** A safe tool ran instead. */
            DOWNGRADED,
            /** The run ended. */
            TERMINATED
        }

        /** Requires every component. */
        public CallDecided {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(callId, "callId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(at, "at");
        }
    }
}
