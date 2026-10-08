package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolId;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Something that happened in a session. A session's state is the fold of its events, so it can
 * always be rebuilt, and later replayed against another policy.
 *
 * <p>Sealed because stores persist these events: they must know every kind they may meet.
 */
public sealed interface SessionEvent
        permits SessionEvent.CallDecided, SessionEvent.ResultRecorded, SessionEvent.Declassified {

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

    /**
     * A tool's output reached the model, bringing its source labels into the session.
     *
     * <p>The output itself is not kept: taint needs only the labels, and outputs will be stored,
     * encrypted, once bound arguments need them.
     *
     * @param sessionId the session
     * @param callId    the call whose tool ran
     * @param tool      the tool that ran, which for a downgrade is the safe tool
     * @param succeeded whether the tool returned normally
     * @param labels    the source labels its output carries; may be empty
     * @param at        when the call was captured
     */
    record ResultRecorded(String sessionId, String callId, ToolId tool, boolean succeeded, Set<Label> labels,
                          Instant at) implements SessionEvent {

        /** Requires every component and copies the labels. */
        public ResultRecorded {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(callId, "callId");
            Objects.requireNonNull(tool, "tool");
            labels = Set.copyOf(labels);
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Application code checked the data and removed a label from the session.
     *
     * @param sessionId     the session
     * @param label         the source label removed
     * @param justification why it is safe, for the audit trail
     * @param at            when
     */
    record Declassified(String sessionId, Label label, String justification, Instant at) implements SessionEvent {

        /** Requires every component. */
        public Declassified {
            requireNonBlank(sessionId, "sessionId");
            Objects.requireNonNull(label, "label");
            requireNonBlank(justification, "justification");
            Objects.requireNonNull(at, "at");
        }
    }
}
