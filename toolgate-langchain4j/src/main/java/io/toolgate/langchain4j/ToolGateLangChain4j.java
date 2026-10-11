package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolService;
import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalItem;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * ToolGate for LangChain4j AI services. Tools are registered through an agent, which wraps their
 * executors and installs ToolGate's {@code beforeToolExecution} hook:
 *
 * <pre>{@code
 * ToolGateLangChain4j toolGate = ToolGateLangChain4j.builder(policy)
 *         .gate(gate -> gate.sessionStore(store).auditSink(sink))
 *         .build();
 *
 * Assistant assistant = toolGate.agent("support-chat")
 *         .tools(new InvoiceTools())
 *         .toolProvider(mcpToolProvider)
 *         .applyTo(AiServices.builder(Assistant.class).chatModel(model))
 *         .build();
 *
 * assistant.chat(question, ToolGateParameters.of(maria, "chat-42"));
 * }</pre>
 *
 * <p>The hook ends the run, with {@link ToolGateViolationException}, on a Terminate decision and on
 * any tool that was not registered through ToolGate. An AI service keeps a single hook: set the
 * application's own with {@link Agent#hook}, never on the builder afterwards, which would replace
 * ToolGate's. Executors registered through ToolGate stay gated either way.
 *
 * <p>Not available for tools registered through ToolGate: LangChain4j's compensating actions
 * ({@code @CompensateFor}), which it discovers only for tools registered as plain objects.
 */
public final class ToolGateLangChain4j implements AutoCloseable {

    private final Gating gating;

    private ToolGateLangChain4j(Gating gating) {
        this.gating = gating;
    }

    /**
     * Starts building, for one policy.
     *
     * @param policy the policy
     * @return the builder
     */
    public static Builder builder(ToolGatePolicy policy) {
        return new Builder(policy);
    }

    /**
     * The gate, for what the adapter does not do itself, such as {@code declassify}.
     *
     * @return the gate
     */
    public ToolGate gate() {
        return gating.gate();
    }

    /**
     * Registers tools for one agent.
     *
     * @param agentId the agent, as the policy names it
     * @return the agent's registration
     */
    public Agent agent(String agentId) {
        return new Agent(agentId);
    }

    /**
     * Every call waiting for review, of every agent and tenant: for operators and the application's
     * own jobs only. A screen a person uses calls {@link #pending(Principal)}.
     *
     * @return the pending calls
     */
    public List<PendingCall> pending() {
        return gating.gate().pending();
    }

    /**
     * The calls waiting for review that a viewer may see, each saying whether the viewer may decide it.
     *
     * @param viewer who is looking
     * @return the calls; see {@link io.toolgate.api.ToolGate#pending(Principal)}
     */
    public List<ApprovalItem> pending(Principal viewer) {
        return gating.gate().pending(viewer);
    }

    /**
     * Approves a deferred call and, if ToolGate releases it, runs it. The tool is found before
     * approving: when this instance cannot run it, nothing is approved and the call stays pending.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @return ToolGate's outcome; on {@link Approval.Released} the tool has run and its result is recorded
     * @throws IllegalArgumentException if no call has that ID, or the principal is not the call's
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal) {
        Optional<PendingCall> waiting = gating.gate().pending().stream()
                .filter(pending -> pending.approvalId().equals(approvalId)).findFirst();
        if (waiting.isEmpty()) {
            // Decided, expired or unknown: ToolGate says which, and nothing will run.
            gating.forget(approvalId);
            return gating.gate().approve(approvalId, reviewer, currentPrincipal);
        }
        ToolCall deferred = waiting.get().call();
        Optional<Gating.Kept> tool = gating.kept(approvalId).or(() -> gating.registered(deferred.tool())
                .map(executor -> new Gating.Kept(executor, ArgValueJson.write(deferred.args()))));
        if (tool.isEmpty()) {
            return new Approval.NotPermitted(deferred.tool() + " cannot run on this instance: it came from a tool provider"
                    + " on another instance, or that instance restarted; register the tool with agent(...).tools(...)");
        }
        GatedToolExecutor executor = tool.get().executor();
        // An instance started after the deferral may not have seen the tool offered yet; the re-check needs it.
        gating.definition(deferred.tool(), executor.specification());
        Approval approval = gating.gate().approve(approvalId, reviewer, currentPrincipal);
        if (approval instanceof Approval.Released released) {
            gating.forget(approvalId);
            ToolCall call = released.call();
            var request = ToolExecutionRequest.builder().id(call.callId()).name(executor.specification().name())
                    .arguments(tool.get().input()).build();
            var context = InvocationContext.builder().chatMemoryId(call.sessionId())
                    .invocationParameters(InvocationParameters.from(Map.of(
                            ToolGateParameters.PRINCIPAL, call.principal(),
                            ToolGateParameters.SESSION, call.sessionId(),
                            ToolGateParameters.IDEMPOTENCY_KEY, released.idempotencyKey())))
                    .build();
            executor.run(executor.target(), call, request, context);
        }
        return approval;
    }

    /**
     * Rejects a deferred call; it will never run.
     *
     * @param approvalId the pending call
     * @param reviewer   who rejects
     * @param reason     why, for the audit trail
     * @return {@code true} if the call was pending and is now rejected
     */
    public boolean reject(String approvalId, Principal reviewer, String reason) {
        boolean rejected = gating.gate().reject(approvalId, reviewer, reason);
        if (rejected) {
            gating.forget(approvalId);
        }
        return rejected;
    }

    /** Closes the gate, flushing its audit events. */
    @Override
    public void close() {
        gating.gate().close();
    }

    /** One agent's tools, registered on an AI service builder with ToolGate's hook. */
    public final class Agent {

        private final String agentId;
        private final List<Object> objectsWithTools = new ArrayList<>();
        private final List<ToolProvider> toolProviders = new ArrayList<>();
        private Consumer<BeforeToolExecution> applicationHook;

        private Agent(String agentId) {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            this.agentId = agentId;
        }

        /**
         * Tools as objects with {@code @Tool} methods, as {@code AiServices.tools(...)} takes them.
         *
         * @param tools the objects
         * @return this registration
         */
        public Agent tools(Object... tools) {
            objectsWithTools.addAll(List.of(tools));
            return this;
        }

        /**
         * A provider of tools, such as LangChain4j's {@code McpToolProvider}; every tool it offers is gated.
         *
         * @param provider the provider
         * @return this registration
         */
        public Agent toolProvider(ToolProvider provider) {
            toolProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        /**
         * The application's own {@code beforeToolExecution} hook. It runs after ToolGate's check, since
         * an AI service keeps a single hook.
         *
         * @param hook the hook
         * @return this registration
         */
        public Agent hook(Consumer<BeforeToolExecution> hook) {
            this.applicationHook = Objects.requireNonNull(hook, "hook");
            return this;
        }

        /**
         * Registers the tools, providers and ToolGate's hook on an AI service builder.
         *
         * @param services the builder
         * @param <T>      the AI service type
         * @return the same builder
         */
        public <T> AiServices<T> applyTo(AiServices<T> services) {
            var tools = new AgentTools(agentId, gating, applicationHook);
            var wrapped = new ArrayList<AiServiceTool>();
            for (Object object : objectsWithTools) {
                for (AiServiceTool tool : ToolService.findTools(object)) {
                    AiServiceTool gated = tools.wrap(tool);
                    gating.register((GatedToolExecutor) gated.toolExecutor());
                    wrapped.add(gated);
                }
            }
            if (!wrapped.isEmpty()) {
                services.tools(wrapped);
            }
            for (ToolProvider provider : toolProviders) {
                services.toolProvider(tools.wrap(provider));
            }
            return services.beforeToolExecution(tools::before);
        }
    }

    /** Builds a {@link ToolGateLangChain4j}. */
    public static final class Builder {

        private final ToolGatePolicy policy;
        private Consumer<ToolGate.Builder> gate = gate -> { };
        private Clock clock = Clock.systemUTC();

        private Builder(ToolGatePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        /**
         * Configures the gate: its session store, approval queue, audit sink and resolvers. Its tool
         * catalog is the adapter's, filled from the tools' specifications.
         *
         * @param configurer receives the gate's builder
         * @return this builder
         */
        public Builder gate(Consumer<ToolGate.Builder> configurer) {
            this.gate = Objects.requireNonNull(configurer, "configurer");
            return this;
        }

        /**
         * The clock that times calls, shared with the gate.
         *
         * @param newClock the clock
         * @return this builder
         */
        public Builder clock(Clock newClock) {
            this.clock = Objects.requireNonNull(newClock, "clock");
            return this;
        }

        /**
         * Builds the adapter and its gate.
         *
         * @return the adapter
         */
        public ToolGateLangChain4j build() {
            ToolCatalog.Mutable catalog = ToolCatalog.inMemory();
            ToolGate.Builder builder = ToolGate.builder(policy);
            gate.accept(builder);
            ToolGate built = builder.toolCatalog(catalog).clock(clock).build();
            return new ToolGateLangChain4j(new Gating(built, catalog, clock, policy));
        }
    }
}
