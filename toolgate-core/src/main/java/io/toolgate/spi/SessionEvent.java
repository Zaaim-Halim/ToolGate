package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Something that happened in a session. A session's state is the fold of its events, so it can
 * always be rebuilt, and later replayed against another policy.
 *
 * <p>Sealed because stores persist these events: they must know every kind they may meet.
 */
public sealed interface SessionEvent
        permits SessionEvent.CallDecided, SessionEvent.ResultRecorded, SessionEvent.Declassified,
                SessionEvent.CallReleased, SessionEvent.CallUnresolved, SessionEvent.CallResolved {

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
     * @param spent      what this call added to the session's totals, by rule ID; empty unless it was
     *                   allowed and an argument has a total limit
     * @param policyHash the policy that decided, as {@code ToolGatePolicy.hash()} gives it
     * @param at         when the call was captured
     */
    record CallDecided(String sessionId, String callId, String agentId, Principal principal, ToolId tool,
                       ObjectValue args, Decision applied, Decision computed, Map<String, BigDecimal> spent,
                       String policyHash, Instant at) implements SessionEvent {

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
            spent = Totals.copy(spent);
            requireNonBlank(policyHash, "policyHash");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A tool's output reached the model, bringing its source labels into the session.
     *
     * <p>The output itself is not kept: taint needs only the labels, and bound arguments only the
     * fingerprints of the trusted fields' values.
     *
     * @param sessionId the session
     * @param callId    the call whose tool ran
     * @param tool      the tool that ran, which for a downgrade is the safe tool
     * @param succeeded whether the tool returned normally
     * @param withheld  whether a result rule kept the output from the model; then it adds no
     *                  labels and counts as a denial
     * @param labels    the source labels its output brought in; empty when withheld
     * @param at        when the call was captured
     * @param trusted   fingerprints of the values of the tool's trusted fields, by
     *                  {@code Binding.sourceKey}; empty when withheld or failed
     */
    record ResultRecorded(String sessionId, String callId, ToolId tool, boolean succeeded, boolean withheld,
                          Set<Label> labels, Instant at, Map<String, Set<String>> trusted) implements SessionEvent {

        /** Requires every component and copies the labels and fingerprints. */
        public ResultRecorded {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(callId, "callId");
            Objects.requireNonNull(tool, "tool");
            labels = Set.copyOf(labels);
            Objects.requireNonNull(at, "at");
            if (withheld && !labels.isEmpty()) {
                throw new IllegalArgumentException("a withheld output never reached the model, so it adds no labels");
            }
            trusted = trusted.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
            if ((withheld || !succeeded) && !trusted.isEmpty()) {
                throw new IllegalArgumentException("only an output the model read can hold trusted values");
            }
        }

        /**
         * A result with no trusted values.
         *
         * @param sessionId the session
         * @param callId    the call whose tool ran
         * @param tool      the tool that ran
         * @param succeeded whether the tool returned normally
         * @param withheld  whether a result rule kept the output from the model
         * @param labels    the source labels its output brought in
         * @param at        when the call was captured
         */
        public ResultRecorded(String sessionId, String callId, ToolId tool, boolean succeeded, boolean withheld,
                              Set<Label> labels, Instant at) {
            this(sessionId, callId, tool, succeeded, withheld, labels, at, Map.of());
        }
    }

    /**
     * A deferred call was approved and released to run: what it adds to the session's totals counts
     * now. Its tool-call budget was already spent when it was deferred.
     *
     * @param sessionId  the session
     * @param approvalId the released call's approval ID
     * @param callId     the call
     * @param spent      what it adds to the session's totals, by rule ID
     * @param at         when it was released
     */
    record CallReleased(String sessionId, String approvalId, String callId, Map<String, BigDecimal> spent, Instant at)
            implements SessionEvent {

        /** Requires every component; amounts must not be negative. */
        public CallReleased {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(approvalId, "approvalId");
            requireNonBlank(callId, "callId");
            spent = Totals.copy(spent);
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * An approved call's run ended with no known outcome: its tool may or may not have run. Until it is
     * resolved, new calls to the same tool in this session are denied.
     *
     * @param sessionId  the session
     * @param approvalId the call's approval ID
     * @param tool       its tool
     * @param at         when the outcome was found lost
     */
    record CallUnresolved(String sessionId, String approvalId, ToolId tool, Instant at) implements SessionEvent {

        /** Requires every component. */
        public CallUnresolved {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(approvalId, "approvalId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * An unresolved call's outcome became known, from its executor or from a person who checked.
     *
     * @param sessionId  the session
     * @param approvalId the call's approval ID
     * @param at         when
     */
    record CallResolved(String sessionId, String approvalId, Instant at) implements SessionEvent {

        /** Requires every component. */
        public CallResolved {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(approvalId, "approvalId");
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
