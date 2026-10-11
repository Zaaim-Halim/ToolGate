package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolId;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something ToolGate decided or did, for audit sinks.
 *
 * <p>Sealed so that sinks can switch over every kind; a new kind is a compile error in a sink that
 * does not handle it.
 */
public sealed interface AuditEvent
        permits AuditEvent.Decided, AuditEvent.PolicyLoaded, AuditEvent.Declassified, AuditEvent.ResultWithheld,
                AuditEvent.ApprovalDecided {

    /**
     * When it happened.
     *
     * @return the time, from the gate's clock
     */
    Instant time();

    /**
     * A call was checked.
     *
     * <p>Arguments are summarized, never logged as given: as keyed hashes when the application
     * supplies a key, otherwise as their type and size. Decision reasons are logged in full, and a
     * reason may quote an argument value, such as a denied host or path.
     *
     * @param time          when the decision was made
     * @param callId        the call
     * @param sessionId     its session
     * @param agentId       the agent that made it
     * @param principal     who the agent acted for
     * @param tool          the tool the model asked for
     * @param args          each argument's summary, by name: {@code hmac:…}, or a type such as {@code text(23)}
     * @param applied       the decision that took effect
     * @param computed      the decision a fully enforced policy would have made
     * @param mode          the policy-wide mode
     * @param toolCalls     tool calls the session has used, after this one
     * @param toolCallLimit the agent's tool-call budget; {@link Integer#MAX_VALUE} when unlimited
     * @param taint         the labels the session held when the call was checked, each with the call
     *                      that brought it in: why a taint rule decided as it did
     * @param policyHash    the policy that decided
     */
    record Decided(Instant time, String callId, String sessionId, String agentId, Principal principal, ToolId tool,
                   Map<String, String> args, Decision applied, Decision computed, Mode mode,
                   int toolCalls, int toolCallLimit, Map<Label, TaintSource> taint, String policyHash)
            implements AuditEvent {

        /** Requires every component and copies the summaries, keeping their order. */
        public Decided {
            Objects.requireNonNull(time, "time");
            requireNonBlank(callId, "callId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(tool, "tool");
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
            Objects.requireNonNull(applied, "applied");
            Objects.requireNonNull(computed, "computed");
            Objects.requireNonNull(mode, "mode");
            taint = taint.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(taint));
            requireNonBlank(policyHash, "policyHash");
        }
    }

    /**
     * A tool's output broke a result rule. In enforce mode the model got a generic message instead;
     * in monitor mode it got the output, and this event records what enforcement would have done.
     *
     * @param time       when the result was checked
     * @param callId     the call whose tool ran
     * @param sessionId  its session
     * @param agentId    the agent
     * @param principal  who the agent acted for
     * @param tool       the tool that ran
     * @param ruleId     the result rule broken
     * @param reason     why; may quote values from the output
     * @param enforced   whether the output was actually withheld
     * @param policyHash the policy that decided
     */
    record ResultWithheld(Instant time, String callId, String sessionId, String agentId, Principal principal,
                          ToolId tool, String ruleId, String reason, boolean enforced, String policyHash)
            implements AuditEvent {

        /** Requires every component. */
        public ResultWithheld {
            Objects.requireNonNull(time, "time");
            requireNonBlank(callId, "callId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(tool, "tool");
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(reason, "reason");
            requireNonBlank(policyHash, "policyHash");
        }
    }

    /**
     * A deferred call left the queue: released, refused on re-check, rejected or expired.
     *
     * @param time       when
     * @param approvalId the call's approval ID
     * @param sessionId  its session
     * @param agentId    the agent that made it
     * @param tool       the tool it calls
     * @param status     how it was decided
     * @param decidedBy  the reviewer's ID, or {@code toolgate} for an expiry
     * @param access     the access the reviewer decided under, such as {@link ApprovalAccess#SUPER_ADMIN};
     *                   null only for ToolGate's own decisions, such as an expiry
     * @param reason     why: the rejection's reason, what changed for a refusal
     */
    record ApprovalDecided(Instant time, String approvalId, String sessionId, String agentId, ToolId tool,
                           ApprovalStatus status, String decidedBy, ApprovalAccess access, String reason)
            implements AuditEvent {

        /** Requires every component, and a final status. */
        public ApprovalDecided {
            Objects.requireNonNull(time, "time");
            requireNonBlank(approvalId, "approvalId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(status, "status");
            if (status == ApprovalStatus.PENDING) {
                throw new IllegalArgumentException("an approval decision has a final status");
            }
            requireNonBlank(decidedBy, "decidedBy");
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * Application code removed a label from a session.
     *
     * @param time          when
     * @param sessionId     the session
     * @param label         the label removed
     * @param justification why the code judged the data safe
     */
    record Declassified(Instant time, String sessionId, Label label, String justification) implements AuditEvent {

        /** Requires every component. */
        public Declassified {
            Objects.requireNonNull(time, "time");
            requireNonBlank(sessionId, "sessionId");
            Objects.requireNonNull(label, "label");
            requireNonBlank(justification, "justification");
        }
    }

    /**
     * A gate started enforcing a policy.
     *
     * @param time       when the gate was built
     * @param policyHash the policy
     * @param mode       its policy-wide mode
     * @param agents     how many agents it defines
     */
    record PolicyLoaded(Instant time, String policyHash, Mode mode, int agents) implements AuditEvent {

        /** Requires every component. */
        public PolicyLoaded {
            Objects.requireNonNull(time, "time");
            requireNonBlank(policyHash, "policyHash");
            Objects.requireNonNull(mode, "mode");
        }
    }
}
