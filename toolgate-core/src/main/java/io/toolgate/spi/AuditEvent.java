package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something ToolGate decided or did, for audit sinks.
 *
 * <p>Sealed so that sinks can switch over every kind; a new kind is a compile error in a sink that
 * does not handle it.
 */
public sealed interface AuditEvent permits AuditEvent.Decided, AuditEvent.PolicyLoaded {

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
     * @param policyHash    the policy that decided
     */
    record Decided(Instant time, String callId, String sessionId, String agentId, Principal principal, ToolId tool,
                   Map<String, String> args, Decision applied, Decision computed, Mode mode,
                   int toolCalls, int toolCallLimit, String policyHash) implements AuditEvent {

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
            requireNonBlank(policyHash, "policyHash");
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
