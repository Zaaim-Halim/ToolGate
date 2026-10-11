package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.memory.ChatMemoryService;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One AI service's tools, as registered through ToolGate for one agent, and ToolGate's hook for it.
 * The hook decides each call; the gated executor applies the decision.
 */
final class AgentTools {

    static final String NO_CONTEXT = "adapter.context";
    static final String UNREGISTERED = "adapter.unregistered";
    private static final System.Logger LOG = System.getLogger("toolgate.adapter");

    private final String agentId;
    private final Gating gating;
    private final Consumer<BeforeToolExecution> applicationHook;
    /** By the name the model sees; tools from a provider are added as the provider offers them. */
    private final Map<String, GatedToolExecutor> byName = new ConcurrentHashMap<>();

    AgentTools(String agentId, Gating gating, Consumer<BeforeToolExecution> applicationHook) {
        this.agentId = agentId;
        this.gating = gating;
        this.applicationHook = applicationHook;
    }

    String agentId() {
        return agentId;
    }

    /** Wraps a tool, so every way LangChain4j runs it goes through ToolGate. */
    AiServiceTool wrap(AiServiceTool tool) {
        return wrap(tool, false);
    }

    /** Wraps a tool; one from an MCP provider is never named as a local tool. */
    AiServiceTool wrap(AiServiceTool tool, boolean fromMcpProvider) {
        var gated = new GatedToolExecutor(tool.toolExecutor(), tool.toolSpecification(),
                McpTools.toolId(tool.toolExecutor(), tool.name(), fromMcpProvider), this, gating);
        byName.put(tool.name(), gated);
        return AiServiceTool.builder()
                .toolSpecification(tool.toolSpecification())
                .toolExecutor(gated)
                .returnBehavior(tool.returnBehavior())
                .build();
    }

    /** Wraps every tool a provider offers, request by request, as MCP tools arrive. */
    ToolProvider wrap(ToolProvider provider) {
        boolean mcp = McpTools.isMcpProvider(provider);
        return new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(ToolProviderRequest request) {
                ToolProviderResult offered = provider.provideTools(request);
                var gated = ToolProviderResult.builder();
                offered.aiServiceTools().forEach(tool -> gated.add(wrap(tool, mcp)));
                return gated.build();
            }

            @Override
            public boolean isDynamic() {
                return provider.isDynamic();
            }
        };
    }

    Optional<GatedToolExecutor> executor(ToolId tool) {
        return byName.values().stream().filter(executor -> executor.toolId().equals(tool)).findFirst();
    }

    /**
     * ToolGate's hook: checks the call, ends the run on Terminate, and hands any other decision to the
     * executor. A tool that was not registered through ToolGate ends the run: it would run unchecked.
     * The application's own hook runs after the check.
     */
    void before(BeforeToolExecution before) {
        if (gating.mode() != Mode.OFF) {
            ToolExecutionRequest request = before.request();
            GatedToolExecutor executor = byName.get(request.name());
            if (executor == null) {
                refuse(UNREGISTERED, "tool " + request.name() + " was not registered through ToolGate for agent "
                        + agentId + ", so it would run unchecked");
            } else {
                try {
                    Gating.Checked checked = check(executor, request, before.invocationContext());
                    if (checked.decision() instanceof Decision.Terminate(String ruleId, String reason)) {
                        throw new ToolGateViolationException(ruleId, reason);
                    }
                    gating.handOver(request, checked);
                } catch (ToolGateViolationException noContext) {
                    if (gating.mode() != Mode.MONITOR || !noContext.ruleId().equals(NO_CONTEXT)) {
                        throw noContext;
                    }
                    LOG.log(System.Logger.Level.WARNING, "monitor mode: {0} runs unchecked: {1}", request.name(),
                            noContext.getMessage());
                }
            }
        }
        if (applicationHook != null) {
            applicationHook.accept(before);
        }
    }

    private void refuse(String ruleId, String reason) {
        if (gating.mode() == Mode.MONITOR) {
            LOG.log(System.Logger.Level.WARNING, "monitor mode: {0}", reason);
            return;
        }
        throw new ToolGateViolationException(ruleId, reason);
    }

    /**
     * Builds the call and decides it. What the adapter cannot read is recorded as a refusal, which
     * counts toward the denial budget.
     *
     * @throws ToolGateViolationException if the call has no principal or no session
     */
    Gating.Checked check(GatedToolExecutor executor, ToolExecutionRequest request, InvocationContext context) {
        Map<String, Object> parameters = parameters(context);
        ObjectValue arguments = arguments(request.arguments());
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), principal(parameters), agentId,
                session(parameters, context), executor.toolId(), arguments == null ? ObjectValue.empty() : arguments,
                gating.clock().instant());
        if (arguments == null) {
            return new Gating.Checked(call, null, gating.gate().reject(call, BuiltInRules.ARGUMENTS_READABLE,
                    "the arguments are not one unambiguous JSON object (duplicate keys, trailing content or not JSON)"));
        }
        Optional<ToolDefinition> own = gating.definition(executor.toolId(), executor.specification());
        if (own.isEmpty()) {
            return new Gating.Checked(call, arguments, gating.gate().reject(call, BuiltInRules.DEFINITION_READABLE,
                    "the tool's definition cannot be read or canonicalized, so pins and declared arguments cannot be checked"));
        }
        // Every tool the agent offers, so a downgrade's safe tool is checked against its own definition too.
        var offered = new HashMap<ToolId, ToolDefinition>();
        for (GatedToolExecutor sibling : byName.values()) {
            gating.definition(sibling.toolId(), sibling.specification())
                    .ifPresent(definition -> offered.putIfAbsent(sibling.toolId(), definition));
        }
        offered.put(executor.toolId(), own.get());
        return new Gating.Checked(call, arguments, gating.gate().check(call, tool -> Optional.ofNullable(offered.get(tool))));
    }

    /**
     * The arguments exactly as the tool will read them, or null. LangChain4j re-reads JSON it cannot
     * parse after stripping quotes, unescaping and dropping trailing commas, and keeps the last of two
     * duplicate keys; the strict parser refuses all of that, so what passes reads the same everywhere.
     */
    private static ObjectValue arguments(String text) {
        if (text == null || text.isBlank()) {
            return ObjectValue.empty();
        }
        try {
            return ArgValueJson.read(text) instanceof ObjectValue object ? object : null;
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    private Principal principal(Map<String, Object> parameters) {
        if (parameters.get(ToolGateParameters.PRINCIPAL) instanceof Principal principal) {
            return principal;
        }
        return gating.declaredPrincipal(agentId).orElseThrow(() -> missing(ToolGateParameters.PRINCIPAL));
    }

    /**
     * The session the parameters name, else the chat memory ID. LangChain4j's memory ID is
     * {@code "default"} for every user of a service without {@code @MemoryId}, which would put everyone
     * in one session, so that one does not count.
     */
    private static String session(Map<String, Object> parameters, InvocationContext context) {
        if (parameters.get(ToolGateParameters.SESSION) instanceof String session && !session.isBlank()) {
            return session;
        }
        Object memoryId = context == null ? null : context.chatMemoryId();
        if (memoryId != null && !ChatMemoryService.DEFAULT.equals(memoryId) && !memoryId.toString().isBlank()) {
            return memoryId.toString();
        }
        throw missing(ToolGateParameters.SESSION);
    }

    private static Map<String, Object> parameters(InvocationContext context) {
        return context == null || context.invocationParameters() == null ? Map.of() : context.invocationParameters().asMap();
    }

    private static ToolGateViolationException missing(String key) {
        return new ToolGateViolationException(NO_CONTEXT, "the invocation parameters have no " + key
                + "; pass ToolGateParameters.of(principal, session) with every call, or give the service a @MemoryId");
    }
}
