package io.toolgate.springai;

import io.toolgate.api.Principal;
import java.util.Map;
import java.util.Objects;

/**
 * The tool context entries ToolGate reads on every tool call. Pass them with each request, so they
 * travel with it to whatever thread runs the tool:
 *
 * <pre>{@code
 * chatClient.prompt().user(question)
 *     .toolContext(ToolGateContext.of("support-chat", principal, conversationId))
 *     .call().content();
 * }</pre>
 *
 * <p>A tool call without them ends the run with a {@link ToolGateViolationException}: ToolGate
 * never guesses who a call is for. They are never sent to MCP servers.
 */
public final class ToolGateContext {

    /** The agent making the calls, as the policy names it. */
    public static final String AGENT = "toolgate.agent";
    /** The {@link Principal} the agent acts for. */
    public static final String PRINCIPAL = "toolgate.principal";
    /** The session that holds taint and budgets, such as the conversation ID or one job run. */
    public static final String SESSION = "toolgate.session";
    /**
     * Set when an approved deferred call runs: the same for every attempt to run that call, so a tool
     * whose API takes an idempotency key can make its effect happen at most once.
     */
    public static final String IDEMPOTENCY_KEY = "toolgate.idempotencyKey";

    private ToolGateContext() {}

    /**
     * The tool context entries for one request.
     *
     * @param agentId   the agent, as the policy names it
     * @param principal who the agent acts for
     * @param sessionId the session, such as the conversation ID
     * @return a map for {@code toolContext(...)}
     */
    public static Map<String, Object> of(String agentId, Principal principal, String sessionId) {
        return Map.of(AGENT, Objects.requireNonNull(agentId, "agentId"),
                PRINCIPAL, Objects.requireNonNull(principal, "principal"),
                SESSION, Objects.requireNonNull(sessionId, "sessionId"));
    }

    /**
     * Whether a tool context key is one of ToolGate's.
     *
     * @param key a tool context key
     * @return {@code true} for ToolGate's keys
     */
    public static boolean isToolGateKey(String key) {
        return AGENT.equals(key) || PRINCIPAL.equals(key) || SESSION.equals(key) || IDEMPOTENCY_KEY.equals(key)
                || ToolGateDeferrals.KEY.equals(key);
    }
}
