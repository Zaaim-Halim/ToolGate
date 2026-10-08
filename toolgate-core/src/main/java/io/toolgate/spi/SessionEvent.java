package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
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
     * A call was checked and a decision applied.
     *
     * <p>Carries the whole call, so the call can be replayed against another policy, and both the
     * applied and the computed decision, so monitor mode keeps what it would have done. The
     * arguments are the real values: stores that persist events must protect them.
     *
     * @param sessionId  the session
     * @param callId     the call
     * @param agentId    the agent that made it
     * @param principal  who the agent acted for
     * @param tool       the tool the model asked for
     * @param args       the arguments the model gave
     * @param applied    the decision that took effect
     * @param computed   the decision a fully enforced policy would have made
     * @param policyHash the policy that decided, as {@code ToolGatePolicy.hash()} gives it
     * @param at         when the call was captured
     */
    record CallDecided(String sessionId, String callId, String agentId, Principal principal, ToolId tool,
                       ObjectValue args, Decision applied, Decision computed, String policyHash, Instant at)
            implements SessionEvent {

        /** Requires every component. */
        public CallDecided {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(callId, "callId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(args, "args");
            Objects.requireNonNull(applied, "applied");
            Objects.requireNonNull(computed, "computed");
            requireNonBlank(policyHash, "policyHash");
            Objects.requireNonNull(at, "at");
        }
    }
}
