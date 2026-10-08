package io.toolgate.api.policy;

import java.util.Set;

/**
 * IDs of the built-in rules, as they appear in decisions and audit events and as
 * {@link ToolGatePolicy.Builder#ruleMode} accepts them.
 */
public final class BuiltInRules {

    /** The call names an agent the policy defines. */
    public static final String AGENT_KNOWN = "agent.known";
    /** A remote tool's MCP server is on the policy's server allow-list. */
    public static final String MCP_SERVERS = "mcp.servers";
    /** The tool is on the agent's allow-list. */
    public static final String ALLOW_LIST = "allow-list";
    /** The session has tool-call budget left. */
    public static final String TOOL_CALL_BUDGET = "budget.toolCalls";
    /** The session has not used up its denial budget. */
    public static final String DENIAL_BUDGET = "budget.denials";

    /** Not a rule: names the decision returned for every call while the policy is off. */
    public static final String MODE_OFF = "mode.off";
    /** Not a rule: names the allow returned in monitor mode for a call that would have been stopped. */
    public static final String MODE_MONITOR = "mode.monitor";
    /** Not a rule: the session store kept rejecting appends, so the call was denied. */
    public static final String SESSION_CONTENTION = "session.contention";
    /** Not a rule: a downgrade's safe tool would itself have been downgraded, so the call was denied. */
    public static final String DOWNGRADE_CHAIN = "downgrade.chain";

    /** Every rule whose mode a policy may set. */
    public static final Set<String> CONFIGURABLE =
            Set.of(AGENT_KNOWN, MCP_SERVERS, ALLOW_LIST, TOOL_CALL_BUDGET, DENIAL_BUDGET);

    private BuiltInRules() {}
}
