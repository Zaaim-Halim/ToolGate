package io.toolgate.springai;

import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * What every gated callback shares: the gate, the catalog it fills from tool definitions, the clock,
 * and the resolver used to find a downgrade's safe tool.
 */
final class Gating {

    /** MCP callbacks live here; until their server is attributed, they are named after no allowed server. */
    private static final String MCP_PACKAGE = "org.springframework.ai.mcp.";
    static final String UNATTRIBUTED_SERVER = "unattributed";

    private final ToolGate gate;
    private final ToolCatalog.Mutable catalog;
    private final Clock clock;
    private final ToolCallbackResolver resolver;
    /** Definitions by what they were built from, so a tool's pin is computed once, not on every call. */
    private final Map<List<String>, Optional<ToolDefinition>> definitions = new ConcurrentHashMap<>();

    Gating(ToolGate gate, ToolCatalog.Mutable catalog, Clock clock, ToolCallbackResolver resolver) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resolver = resolver;
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

    ToolId toolId(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        return callback.getClass().getName().startsWith(MCP_PACKAGE)
                ? ToolId.mcp(UNATTRIBUTED_SERVER, name)
                : ToolId.local(name);
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
