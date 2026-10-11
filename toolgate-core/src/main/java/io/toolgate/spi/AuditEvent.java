package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolId;
import io.toolgate.audit.AuditJson;
import java.time.Duration;
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
                AuditEvent.ApprovalDecided, AuditEvent.ApprovalRecorded, AuditEvent.SuperAdminBurst,
                AuditEvent.ApprovalViewed, AuditEvent.ApprovalsListed, AuditEvent.ApprovalExpiring {

    /**
     * When it happened.
     *
     * @return the time, from the gate's clock
     */
    Instant time();

    /**
     * This event as one line of JSON, exactly as the {@code toolgate.audit} log writes it, for sinks that
     * store or forward events, such as a database table, a SIEM or a message queue. Arguments are
     * summarized as in the log; reasons and comments are kept as written, and may quote argument values.
     *
     * @return a single line of JSON, without a line break
     */
    default String toJson() {
        return AuditJson.format(this);
    }

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
     * A call still waits for a decision and expires soon, so reviewers can be reminded. Raised once per call,
     * by {@code sweep()} on whichever instance runs it first.
     *
     * @param time       when the sweep noticed
     * @param approvalId the call's approval ID
     * @param sessionId  its session
     * @param agentId    the agent that made it
     * @param tool       the tool it calls
     * @param expiresAt  when it expires if nobody decides
     */
    record ApprovalExpiring(Instant time, String approvalId, String sessionId, String agentId, ToolId tool,
                            Instant expiresAt) implements AuditEvent {

        /** Requires every component. */
        public ApprovalExpiring {
            Objects.requireNonNull(time, "time");
            requireNonBlank(approvalId, "approvalId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * An approval was recorded, and the call still waits for another: a super admin's approval under
     * {@code superAdminConfirmation}.
     *
     * @param time       when
     * @param approvalId the call's approval ID
     * @param sessionId  its session
     * @param agentId    the agent that made it
     * @param tool       the tool it calls
     * @param decidedBy  the approver's ID
     * @param access     the access they approved under
     * @param comment    their justification
     */
    record ApprovalRecorded(Instant time, String approvalId, String sessionId, String agentId, ToolId tool,
                            String decidedBy, ApprovalAccess access, String comment) implements AuditEvent {

        /** Requires every component. */
        public ApprovalRecorded {
            Objects.requireNonNull(time, "time");
            requireNonBlank(approvalId, "approvalId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(tool, "tool");
            requireNonBlank(decidedBy, "decidedBy");
            Objects.requireNonNull(access, "access");
            requireNonBlank(comment, "comment");
        }
    }

    /**
     * A super admin or an auditor opened a deferred call, which may be another tenant's: reading its
     * arguments is worth a trace even when nothing is decided.
     *
     * @param time       when
     * @param approvalId the call's approval ID
     * @param sessionId  its session
     * @param agentId    the agent that made it
     * @param tool       the tool it calls
     * @param viewer     who opened it
     * @param access     the access they viewed it under
     */
    record ApprovalViewed(Instant time, String approvalId, String sessionId, String agentId, ToolId tool,
                          String viewer, ApprovalAccess access) implements AuditEvent {

        /** Requires every component. */
        public ApprovalViewed {
            Objects.requireNonNull(time, "time");
            requireNonBlank(approvalId, "approvalId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(agentId, "agentId");
            Objects.requireNonNull(tool, "tool");
            requireNonBlank(viewer, "viewer");
            Objects.requireNonNull(access, "access");
        }
    }

    /**
     * A super admin or an auditor listed the deferred calls they may see, which span tenants. One event per
     * listing, not per call, so a screen that polls does not flood the log.
     *
     * @param time   when
     * @param viewer who listed them
     * @param access the access they listed under
     * @param calls  how many calls the list held
     */
    record ApprovalsListed(Instant time, String viewer, ApprovalAccess access, int calls) implements AuditEvent {

        /** Requires every component. */
        public ApprovalsListed {
            Objects.requireNonNull(time, "time");
            requireNonBlank(viewer, "viewer");
            Objects.requireNonNull(access, "access");
            if (calls < 0) {
                throw new IllegalArgumentException("calls must not be negative");
            }
        }
    }

    /**
     * Super admins made more decisions in a short time than is normal: worth paging someone, since a
     * stolen super admin account tends to act fast.
     *
     * @param time        when the burst was noticed
     * @param decisions   how many super admin decisions the window held
     * @param window      the window
     * @param lastBy      who made the decision that crossed the threshold
     */
    record SuperAdminBurst(Instant time, int decisions, Duration window, String lastBy) implements AuditEvent {

        /** Requires every component. */
        public SuperAdminBurst {
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(window, "window");
            requireNonBlank(lastBy, "lastBy");
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
