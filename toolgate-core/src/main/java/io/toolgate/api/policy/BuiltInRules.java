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
    /** A pinned tool's current definition is still the one the policy pins. */
    public static final String TOOL_PINNED = "tool.pinned";
    /**
     * A call to a tool with argument rules passes only arguments its definition declares, and every
     * ruled argument is declared.
     */
    public static final String ARGUMENTS_DECLARED = "arguments.declared";
    /** The session has tool-call budget left. */
    public static final String TOOL_CALL_BUDGET = "budget.toolCalls";
    /** The session has not used up its denial budget. */
    public static final String DENIAL_BUDGET = "budget.denials";
    /** The adapter could not read the call's arguments unambiguously, so no rule could check them. */
    public static final String ARGUMENTS_READABLE = "arguments.readable";
    /** The adapter could not read the tool's definition, so neither pins nor declared arguments could be checked. */
    public static final String DEFINITION_READABLE = "definition.readable";
    /** Refusals an adapter records through {@code ToolGate.reject}. */
    public static final Set<String> ADAPTER_REFUSALS = Set.of(ARGUMENTS_READABLE, DEFINITION_READABLE);

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
    /**
     * While an approved call of a tool has an unknown outcome in a session, new calls to that tool in
     * that session are denied: nobody knows whether the first refund went out.
     */
    public static final String APPROVAL_UNRESOLVED = "approval.unresolved";

    /** Every rule whose mode a policy may set. */
    public static final Set<String> CONFIGURABLE =
            Set.of(AGENT_KNOWN, MCP_SERVERS, ALLOW_LIST, TOOL_PINNED, ARGUMENTS_DECLARED, ARGUMENTS_READABLE,
                    DEFINITION_READABLE, TOOL_CALL_BUDGET, DENIAL_BUDGET, TAINT_TRIFECTA,
                    TAINT_IRREVERSIBLE, APPROVAL_UNRESOLVED);

    private BuiltInRules() {}
}
