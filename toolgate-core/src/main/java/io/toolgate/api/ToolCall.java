package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.time.Instant;
import java.util.Objects;

/**
 * One tool call the model asked for, as an adapter captured it before the tool runs.
 *
 * <p>Known limit: every check counts against the session's budgets, even when a framework retries
 * a call with the same {@code callId}. Calls are not deduplicated by ID, because some providers
 * reuse IDs, and a cached decision for one call would then apply to a different tool.
 *
 * @param callId    unique within the session; links audit events and makes releases idempotent
 * @param principal who the agent acts for
 * @param agentId   the agent, as named in the policy
 * @param sessionId the conversation or job run whose state this call reads and updates
 * @param tool      the tool, with its source
 * @param args      the arguments exactly as the tool will receive them
 * @param at        when the adapter captured the call
 */
public record ToolCall(String callId, Principal principal, String agentId, String sessionId,
                       ToolId tool, ObjectValue args, Instant at) {

    /** Validates every component, so the engine never sees a half-built call. */
    public ToolCall {
        requireNonBlank(callId, "callId");
        Objects.requireNonNull(principal, "principal");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(sessionId, "sessionId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(at, "at");
    }

    /**
     * The same call aimed at another tool with other arguments, as a downgrade runs it.
     *
     * @param otherTool the tool to call instead
     * @param otherArgs the arguments for that tool
     * @return a call with the same ID, principal, agent, session and time
     */
    public ToolCall withTool(ToolId otherTool, ObjectValue otherArgs) {
        return new ToolCall(callId, principal, agentId, sessionId, otherTool, otherArgs, at);
    }
}
