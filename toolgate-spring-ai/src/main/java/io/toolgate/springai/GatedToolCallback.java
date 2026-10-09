package io.toolgate.springai;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Checks a tool call with ToolGate before the tool runs, applies the decision, and records the result.
 * Spring AI hands a callback only the arguments and the tool context, so the call ID is generated here.
 */
final class GatedToolCallback implements ToolCallback {

    private static final String NO_CONTEXT = "adapter.context";

    private final ToolCallback target;
    private final List<ToolCallback> siblings;
    private final Gating gating;

    GatedToolCallback(ToolCallback target, List<ToolCallback> siblings, Gating gating) {
        this.target = target;
        this.siblings = siblings;
        this.gating = gating;
    }

    ToolCallback target() {
        return target;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return target.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return target.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        ToolId tool = gating.toolId(target);
        ObjectValue arguments = arguments(toolInput);
        if (arguments == null) {
            // Without the arguments the tool will see, no rule can be checked. Not audited: no call can be built.
            return Decision.Deny.MODEL_MESSAGE;
        }
        gating.define(tool, target);
        var call = new ToolCall(UUID.randomUUID().toString(), principal(context), text(context, ToolGateContext.AGENT),
                text(context, ToolGateContext.SESSION), tool, arguments, gating.clock().instant());
        return switch (gating.gate().check(call)) {
            case Decision.Allow allow -> run(target, call, toolInput, toolContext);
            case Decision.Deny deny -> Decision.Deny.MODEL_MESSAGE;
            case Decision.Defer defer -> Decision.Defer.MODEL_MESSAGE;
            case Decision.Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) -> gating.safeTool(safeTool, siblings)
                    .map(safe -> run(safe, new ToolCall(call.callId(), call.principal(), call.agentId(), call.sessionId(),
                            safeTool, safeArgs, call.at()), safeInput(toolInput, arguments, safeArgs), toolContext))
                    // The policy names a safe tool the application does not have; fail closed.
                    .orElse(Decision.Deny.MODEL_MESSAGE);
            case Decision.Terminate(String ruleId, String reason) -> throw new ToolGateViolationException(ruleId, reason);
        };
    }

    /** Runs a tool, records what it returned, and gives the model the output unless a result rule withholds it. */
    private String run(ToolCallback callback, ToolCall callThatRuns, String input, ToolContext toolContext) {
        String output;
        try {
            output = callback.call(input, Gating.isMcp(callback) ? withoutToolGateKeys(toolContext) : toolContext);
        } catch (RuntimeException failure) {
            // An error message can carry an attacker's page as easily as a result, so it taints too.
            gating.gate().recordResult(callThatRuns, new ToolResult.Failed(String.valueOf(failure.getMessage())));
            throw failure;
        }
        String shown = output == null ? "" : output;
        return switch (gating.gate().recordResult(callThatRuns, new ToolResult.Succeeded(result(shown)))) {
            case ResultDecision.Release release -> shown;
            case ResultDecision.Withhold withhold -> ResultDecision.Withhold.MODEL_MESSAGE;
        };
    }

    /**
     * The safe tool gets the model's own text when its arguments are unchanged, the usual case, since
     * re-writing them would turn 480 into {@code 4.8E+2} for a tool that reads it as text.
     */
    private static String safeInput(String toolInput, ObjectValue arguments, ObjectValue safeArgs) {
        if (!safeArgs.equals(arguments)) {
            return ArgValueJson.write(safeArgs);
        }
        return toolInput == null || toolInput.isBlank() ? "{}" : toolInput;
    }

    /**
     * The arguments exactly as the tool will read them, or null. Spring AI treats empty input as
     * {@code {}}; anything a lenient parser could read differently, such as duplicate keys or
     * trailing content, is refused by the strict parser.
     */
    private static ObjectValue arguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return ObjectValue.empty();
        }
        try {
            return ArgValueJson.read(toolInput) instanceof ObjectValue object ? object : null;
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * Spring AI sends an MCP tool's whole tool context to its server as {@code _meta}; the principal
     * and session are no business of a third party.
     */
    private static ToolContext withoutToolGateKeys(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        var kept = new HashMap<String, Object>(toolContext.getContext());
        kept.keySet().removeIf(ToolGateContext::isToolGateKey);
        return new ToolContext(kept);
    }

    private static ArgValue result(String output) {
        try {
            return ArgValueJson.read(output);
        } catch (IllegalArgumentException notJson) {
            return ArgValue.text(output);
        }
    }

    private static Principal principal(Map<String, Object> context) {
        if (context.get(ToolGateContext.PRINCIPAL) instanceof Principal principal) {
            return principal;
        }
        throw missing(ToolGateContext.PRINCIPAL);
    }

    private static String text(Map<String, Object> context, String key) {
        if (context.get(key) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw missing(key);
    }

    private static ToolGateViolationException missing(String key) {
        return new ToolGateViolationException(NO_CONTEXT, "the tool context has no " + key
                + "; pass ToolGateContext.of(agent, principal, session) with every request");
    }
}
