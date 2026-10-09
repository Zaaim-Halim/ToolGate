package io.toolgate.springai;

import io.toolgate.api.Mode;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * What every gated callback shares: the gate, the catalog it fills from tool definitions, the clock,
 * and the resolver used to find a downgrade's safe tool.
 */
final class Gating {

    /** MCP callbacks live here. */
    private static final String MCP_PACKAGE = "org.springframework.ai.mcp.";
    /** The server of an MCP tool whose connection is unknown or ambiguous; no policy allows it. */
    static final String UNATTRIBUTED_SERVER = "unattributed";

    private final ToolGate gate;
    private final ToolCatalog.Mutable catalog;
    private final Clock clock;
    private final ToolCallbackResolver resolver;
    private final Mode mode;
    private final ToolGatePrincipalResolver principals;
    private final String defaultAgent;
    /** Definitions by what they were built from, so a tool's pin is computed once, not on every call. */
    private final Map<List<String>, Optional<ToolDefinition>> definitions = new ConcurrentHashMap<>();

    Gating(ToolGate gate, ToolCatalog.Mutable catalog, Clock clock, ToolCallbackResolver resolver, Mode mode,
           ToolGatePrincipalResolver principals, String defaultAgent) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resolver = resolver;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.principals = principals;
        this.defaultAgent = defaultAgent;
    }

    /** The policy-wide mode, which also decides whether the adapter's own refusals block. */
    Mode mode() {
        return mode;
    }

    /**
     * Fills what a request's tool context leaves out, on the request's own thread: the principal from
     * the resolver, the agent from {@code toolgate.agent}, the session from the conversation ID.
     * Whatever the request passed wins.
     */
    Map<String, Object> completeContext(Map<String, Object> toolContext, Map<String, Object> requestContext) {
        var complete = new HashMap<String, Object>(toolContext == null ? Map.of() : toolContext);
        if (!complete.containsKey(ToolGateContext.PRINCIPAL) && principals != null) {
            principals.resolve().ifPresent(principal -> complete.put(ToolGateContext.PRINCIPAL, principal));
        }
        if (!complete.containsKey(ToolGateContext.AGENT) && defaultAgent != null && !defaultAgent.isBlank()) {
            complete.put(ToolGateContext.AGENT, defaultAgent);
        }
        if (!complete.containsKey(ToolGateContext.SESSION)
                && requestContext.get(ChatMemory.CONVERSATION_ID) instanceof String conversation && !conversation.isBlank()) {
            complete.put(ToolGateContext.SESSION, conversation);
        }
        return complete;
    }

    ToolGate gate() {
        return gate;
    }

    Clock clock() {
        return clock;
    }

    /** Wraps every callback of one request; each knows the others, to find a downgrade's safe tool. */
    List<ToolCallback> wrap(List<ToolCallback> callbacks) {
        List<ToolCallback> targets = callbacks.stream().map(Gating::unwrap).toList();
        return targets.stream().map(target -> (ToolCallback) new GatedToolCallback(target, targets, this)).toList();
    }

    ToolCallback wrap(ToolCallback callback) {
        return callback instanceof GatedToolCallback ? callback : new GatedToolCallback(callback, List.of(), this);
    }

    static ToolCallback unwrap(ToolCallback callback) {
        return callback instanceof GatedToolCallback gated ? gated.target() : callback;
    }

    static boolean isMcp(ToolCallback callback) {
        return callback.getClass().getName().startsWith(MCP_PACKAGE);
    }

    /** A local tool by its name; an MCP tool with the configured connection it came from. */
    ToolId toolId(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        if (!isMcp(callback)) {
            return ToolId.local(name);
        }
        String server = McpConnections.connectionOf(callback).orElse(UNATTRIBUTED_SERVER);
        try {
            return ToolId.mcp(server, name);
        } catch (IllegalArgumentException unusableName) {
            return ToolId.mcp(UNATTRIBUTED_SERVER, name);
        }
    }

    /**
     * Puts the definition the model is offered into the catalog, so pins and declared arguments are
     * checked against exactly what this callback declares. A definition that cannot be canonicalized
     * stays out, which denies the tool wherever a definition is needed.
     */
    void define(ToolId tool, ToolCallback callback) {
        var source = callback.getToolDefinition();
        definitions.computeIfAbsent(List.of(tool.toString(), source.description(), source.inputSchema()), key -> {
            try {
                return Optional.of(ToolDefinition.of(tool, source.description(), source.inputSchema()));
            } catch (IllegalArgumentException uncanonical) {
                return Optional.empty();
            }
        }).ifPresentOrElse(catalog::put, () -> catalog.remove(tool));
    }

    /** The callback for a downgrade's safe tool: one offered with the request, else one the application registered. */
    Optional<ToolCallback> safeTool(ToolId safeTool, List<ToolCallback> siblings) {
        Optional<ToolCallback> offered = siblings.stream().filter(callback -> toolId(callback).equals(safeTool)).findFirst();
        if (offered.isPresent() || resolver == null || !safeTool.isLocal()) {
            return offered;
        }
        return Optional.ofNullable(resolver.resolve(safeTool.name())).map(Gating::unwrap);
    }
}
