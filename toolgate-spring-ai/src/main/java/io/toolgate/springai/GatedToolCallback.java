package io.toolgate.springai;

import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final System.Logger LOG = System.getLogger("toolgate.adapter");

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
        // Marks the run, so the application's tool beans know this call went through ToolGate.
        return ToolBeanGuard.gatedRun(() -> checkAndRun(toolInput, toolContext));
    }

    private String checkAndRun(String toolInput, ToolContext toolContext) {
        if (gating.mode() == Mode.OFF) {
            // The emergency switch: tools run straight through, as if ToolGate were not there.
            return target.call(toolInput, Gating.forTool(target, toolContext));
        }
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        ToolId tool = gating.toolId(target);
        ObjectValue arguments = arguments(toolInput);
        ToolCall call;
        try {
            String agent = text(context, ToolGateContext.AGENT);
            call = new ToolCall(UUID.randomUUID().toString(), principal(context, agent), agent,
                    text(context, ToolGateContext.SESSION), tool, arguments == null ? ObjectValue.empty() : arguments,
                    gating.clock().instant());
        } catch (ToolGateViolationException noContext) {
            if (gating.mode() == Mode.MONITOR) {
                // Without a principal no call can be recorded, so the warning is the only trace.
                LOG.log(System.Logger.Level.WARNING, "monitor mode: {0} ran unchecked: {1}", tool, noContext.getMessage());
                return target.call(toolInput, Gating.forTool(target, toolContext));
            }
            throw noContext;
        }
        return apply(decide(call, arguments), call, arguments, toolInput, toolContext);
    }

    /**
     * Checks the call with the definition this callback offers, so concurrent requests offering another
     * definition under the same name cannot interfere; what the adapter cannot read is recorded as a refusal,
     * which counts toward the denial budget.
     */
    private Decision decide(ToolCall call, ObjectValue arguments) {
        if (arguments == null) {
            return gating.gate().reject(call, BuiltInRules.ARGUMENTS_READABLE,
                    "the arguments are not one unambiguous JSON object (duplicate keys, trailing content or not JSON)");
        }
        Optional<io.toolgate.api.ToolDefinition> own = gating.definition(call.tool(), target);
        if (own.isEmpty()) {
            return gating.gate().reject(call, BuiltInRules.DEFINITION_READABLE,
                    "the tool's definition cannot be canonicalized (RFC 8785), so pins and declared arguments cannot be checked");
        }
        // Everything the request offered, so a downgrade's safe tool is checked against its own definition too.
        var offered = new HashMap<ToolId, io.toolgate.api.ToolDefinition>();
        offered.put(call.tool(), own.get());
        for (ToolCallback sibling : siblings) {
            ToolId siblingTool = gating.toolId(sibling);
            if (!offered.containsKey(siblingTool)) {
                gating.definition(siblingTool, sibling).ifPresent(definition -> offered.put(siblingTool, definition));
            }
        }
        return gating.gate().check(call, tool -> Optional.ofNullable(offered.get(tool)));
    }

    private String apply(Decision decision, ToolCall call, ObjectValue arguments, String toolInput, ToolContext toolContext) {
        return switch (decision) {
            case Decision.Allow allow -> gating.run(target, call, toolInput, toolContext);
            case Decision.Deny deny -> Decision.Deny.MODEL_MESSAGE;
            case Decision.Defer defer -> {
                gating.keep(defer.approvalId(), target, toolInput);
                yield Decision.Defer.MODEL_MESSAGE;
            }
            case Decision.Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) -> gating.safeTool(safeTool, siblings)
                    .map(safe -> gating.run(safe, call.withTool(safeTool, safeArgs), safeInput(toolInput, arguments, safeArgs),
                            toolContext))
                    // The policy names a safe tool the application does not have; fail closed.
                    .orElse(Decision.Deny.MODEL_MESSAGE);
            case Decision.Terminate(String ruleId, String reason) -> throw new ToolGateViolationException(ruleId, reason);
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
     * The request's principal, or the service identity the policy gives the agent. Nothing else is
     * guessed here: this may run on another thread than the request, where a job and a web request
     * look alike; ToolGate's advisor already filled in what the request's own thread could tell.
     */
    private Principal principal(Map<String, Object> context, String agent) {
        if (context.get(ToolGateContext.PRINCIPAL) instanceof Principal principal) {
            return principal;
        }
        return gating.declaredPrincipal(agent).orElseThrow(() -> missing(ToolGateContext.PRINCIPAL));
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
