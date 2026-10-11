package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every gated executor and hook of one {@link ToolGateLangChain4j} shares: the gate, the catalog
 * it fills from tool specifications, the decisions the hook hands to executors, and the executors an
 * approval may need.
 */
final class Gating {

    /** Bounds for state an abandoned call could leave behind; past them the oldest entries go. */
    private static final int MAX_HANDED_OVER = 10_000;
    private static final int MAX_KEPT_CALLS = 10_000;
    private static final int MAX_DEFINITIONS = 10_000;

    private final ToolGate gate;
    private final ToolCatalog.Mutable catalog;
    private final Clock clock;
    private final ToolGatePolicy policy;
    private final Map<List<String>, Optional<ToolDefinition>> definitions = new ConcurrentHashMap<>();
    private final Map<ToolId, GatedToolExecutor> registered = new ConcurrentHashMap<>();

    /**
     * Decisions the hook made, waiting for the executor of the same request. Keyed by the request
     * object, which LangChain4j passes to the hook and then to the executor, never by the model's call
     * ID, which providers may reuse. An entry is left behind only when the run ends between the two,
     * such as when an application hook after ToolGate's throws; the bound clears those.
     */
    private final Map<Identity, Checked> handedOver = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Identity, Checked> eldest) {
            return size() > MAX_HANDED_OVER;
        }
    };

    private final Map<String, Kept> kept = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
            return size() > MAX_KEPT_CALLS;
        }
    };

    /** A call ToolGate decided, with the arguments it read. */
    record Checked(ToolCall call, ObjectValue arguments, Decision decision) {}

    /** A deferred call's executor and the model's own argument text, for running it once approved. */
    record Kept(GatedToolExecutor executor, String input) {}

    /** A request by identity. */
    private record Identity(ToolExecutionRequest request) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity(ToolExecutionRequest otherRequest) && otherRequest == request;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(request);
        }
    }

    Gating(ToolGate gate, ToolCatalog.Mutable catalog, Clock clock, ToolGatePolicy policy) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    ToolGate gate() {
        return gate;
    }

    Clock clock() {
        return clock;
    }

    /** The policy-wide mode, which also decides whether the adapter's own refusals block. */
    Mode mode() {
        return policy.mode();
    }

    /** The service identity the policy gives an agent. */
    Optional<Principal> declaredPrincipal(String agentId) {
        return policy.agent(agentId).flatMap(AgentPolicy::servicePrincipal);
    }

    /**
     * The definition a tool offers the model, computed once per definition; it also goes into the
     * catalog, for what is checked later without the tool at hand, such as an approval's re-check.
     */
    Optional<ToolDefinition> definition(ToolId tool, ToolSpecification specification) {
        if (definitions.size() > MAX_DEFINITIONS) {
            definitions.clear();
        }
        Optional<ToolDefinition> definition = definitions.computeIfAbsent(
                List.of(tool.toString(), String.valueOf(specification.description()), String.valueOf(specification.parameters())),
                key -> ToolSpecs.definition(tool, specification));
        definition.ifPresent(catalog::put);
        return definition;
    }

    void handOver(ToolExecutionRequest request, Checked checked) {
        synchronized (handedOver) {
            handedOver.put(new Identity(request), checked);
        }
    }

    Optional<Checked> takeHandedOver(ToolExecutionRequest request) {
        synchronized (handedOver) {
            return Optional.ofNullable(handedOver.remove(new Identity(request)));
        }
    }

    /** Remembers a tool registered at startup, so an approval on an instance started later can run it. */
    void register(GatedToolExecutor executor) {
        registered.putIfAbsent(executor.toolId(), executor);
    }

    Optional<GatedToolExecutor> registered(ToolId tool) {
        return Optional.ofNullable(registered.get(tool));
    }

    void keep(String approvalId, GatedToolExecutor executor, String input) {
        synchronized (kept) {
            kept.put(approvalId, new Kept(executor, input));
        }
    }

    Optional<Kept> kept(String approvalId) {
        synchronized (kept) {
            return Optional.ofNullable(kept.get(approvalId));
        }
    }

    void forget(String approvalId) {
        synchronized (kept) {
            kept.remove(approvalId);
        }
    }
}
