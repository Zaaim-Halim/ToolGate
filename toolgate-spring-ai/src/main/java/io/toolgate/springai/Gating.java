package io.toolgate.springai;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Mode;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
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
    /** Deferred calls whose tools are kept for approval; enough for any review queue, bounded for memory. */
    private static final int MAX_KEPT_CALLS = 10_000;

    private final ToolGate gate;
    private final ToolCatalog.Mutable catalog;
    private final Clock clock;
    private final ToolCallbackResolver resolver;
    private final Mode mode;
    private final ToolGatePrincipalResolver principals;
    private final String defaultAgent;
    /** Definitions by what they were built from, so a tool's pin is computed once, not on every call. */
    private final Map<List<String>, Optional<ToolDefinition>> definitions = new ConcurrentHashMap<>();
    /** Deferred calls' tools, oldest dropped first past the bound; guarded by itself. */
    private final Map<String, Kept> deferred = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
            return size() > MAX_KEPT_CALLS;
        }
    };

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
     * The definition this callback offers the model, computed once per definition, which the call is
     * checked against. It also goes into the catalog, for what is checked later without a callback at
     * hand: a downgrade's safe tool, and the re-check when a deferred call is approved.
     *
     * @return empty if the definition cannot be canonicalized
     */
    Optional<ToolDefinition> definition(ToolId tool, ToolCallback callback) {
        var source = callback.getToolDefinition();
        Optional<ToolDefinition> definition = definitions.computeIfAbsent(
                List.of(tool.toString(), source.description(), source.inputSchema()), key -> {
                    try {
                        return Optional.of(ToolDefinition.of(tool, source.description(), source.inputSchema()));
                    } catch (IllegalArgumentException uncanonical) {
                        return Optional.empty();
                    }
                });
        definition.ifPresent(catalog::put);
        return definition;
    }

    /**
     * Keeps the callback and the model's own argument text of a deferred call, so an approval can run
     * the very tool the request offered. Per instance and bounded; tools registered as beans are also
     * found through the resolver.
     */
    void keep(String approvalId, ToolCallback callback, String input) {
        synchronized (deferred) {
            deferred.put(approvalId, new Kept(unwrap(callback), input));
        }
    }

    /** A deferred call's tool and argument text, once, for running it after approval. */
    Optional<Kept> kept(String approvalId) {
        synchronized (deferred) {
            return Optional.ofNullable(deferred.get(approvalId));
        }
    }

    void forget(String approvalId) {
        synchronized (deferred) {
            deferred.remove(approvalId);
        }
    }

    /** A local tool the application registered, for an approved call whose callback was not kept. */
    Optional<ToolCallback> registered(ToolId tool) {
        return resolver == null || !tool.isLocal() ? Optional.empty() : Optional.ofNullable(resolver.resolve(tool.name())).map(Gating::unwrap);
    }

    /** A deferred call's tool and the model's argument text. */
    record Kept(ToolCallback callback, String input) {}

    /** Runs a tool, records what it returned, and gives back the output unless a result rule withholds it. */
    String run(ToolCallback callback, ToolCall callThatRuns, String input, ToolContext toolContext) {
        String output;
        try {
            output = callback.call(input, forTool(callback, toolContext));
        } catch (RuntimeException failure) {
            // An error message can carry an attacker's page as easily as a result, so it taints too.
            gate.recordResult(callThatRuns, new ToolResult.Failed(String.valueOf(failure.getMessage())));
            throw failure;
        }
        String shown = output == null ? "" : output;
        return switch (gate.recordResult(callThatRuns, new ToolResult.Succeeded(result(shown)))) {
            case ResultDecision.Release release -> shown;
            case ResultDecision.Withhold withhold -> ResultDecision.Withhold.MODEL_MESSAGE;
        };
    }

    /**
     * The tool context a tool may see. Spring AI sends an MCP tool's whole tool context to its server as
     * {@code _meta}; the principal and session are no business of a third party.
     */
    static ToolContext forTool(ToolCallback callback, ToolContext toolContext) {
        if (toolContext == null || !isMcp(callback)) {
            return toolContext;
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

    /** The callback for a downgrade's safe tool: one offered with the request, else one the application registered. */
    Optional<ToolCallback> safeTool(ToolId safeTool, List<ToolCallback> siblings) {
        Optional<ToolCallback> offered = siblings.stream().filter(callback -> toolId(callback).equals(safeTool)).findFirst();
        if (offered.isPresent() || resolver == null || !safeTool.isLocal()) {
            return offered;
        }
        return Optional.ofNullable(resolver.resolve(safeTool.name())).map(Gating::unwrap);
    }
}
