package io.toolgate.api.policy;

import java.util.Set;

/**
 * IDs of the built-in rules, as they appear in decisions and audit events and as
 * {@link ToolGatePolicy.Builder#ruleMode} accepts them.
 */
public final class BuiltInRules {

    /**
     * The call names an agent the policy defines.
     *
     * <p>Known limit: calls from an unknown agent are denied but never count toward the denial
     * budget, which is configured per agent. The application, not the model, sets the agent ID.
     */
    public static final String AGENT_KNOWN = "agent.known";
    /** A remote tool's MCP server is on the policy's server allow-list. */
    public static final String MCP_SERVERS = "mcp.servers";
    /** The tool is on the agent's allow-list. */
    public static final String ALLOW_LIST = "allow-list";
    /** The session has tool-call budget left. */
    public static final String TOOL_CALL_BUDGET = "budget.toolCalls";
    /** The session has not used up its denial budget. */
    public static final String DENIAL_BUDGET = "budget.denials";
    /** A session holding private data and untrusted content may not call an external sink. */
    public static final String TAINT_TRIFECTA = "taint.trifecta";
    /** A session holding untrusted content may not call an irreversible tool without review. */
    public static final String TAINT_IRREVERSIBLE = "taint.irreversible";

    /** Not a rule: names the decision returned for every call while the policy is off. */
    public static final String MODE_OFF = "mode.off";
    /** Not a rule: names the allow returned in monitor mode for a call that would have been stopped. */
    public static final String MODE_MONITOR = "mode.monitor";
    /** Not a rule: the session store kept rejecting appends, so the call was denied. */
    public static final String SESSION_CONTENTION = "session.contention";
    /** Not a rule: the session store failed, so the run was ended rather than decided without state. */
    public static final String SESSION_UNAVAILABLE = "session.unavailable";
    /** Not a rule: the approval queue failed, so a call that needed review was denied instead. */
    public static final String APPROVAL_UNAVAILABLE = "approval.unavailable";
    /** Not a rule: a downgrade's safe tool would itself have been downgraded, so the call was denied. */
    public static final String DOWNGRADE_CHAIN = "downgrade.chain";

    /** Every rule whose mode a policy may set. */
    public static final Set<String> CONFIGURABLE =
            Set.of(AGENT_KNOWN, MCP_SERVERS, ALLOW_LIST, TOOL_CALL_BUDGET, DENIAL_BUDGET, TAINT_TRIFECTA,
                    TAINT_IRREVERSIBLE);

    private BuiltInRules() {}
}
