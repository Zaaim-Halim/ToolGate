package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolService;
import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.ToolCatalog;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
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
    private final String executorId;
    private final Duration lease;

    private ToolGateLangChain4j(Gating gating, String executorId, Duration lease) {
        this.gating = gating;
        this.executorId = executorId;
        this.lease = lease;
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
     * The calls waiting for review that a viewer may see, each saying whether the viewer may decide it, masked by the policy's review view.
     *
     * @param viewer who is looking
     * @return the calls as review items; see {@link io.toolgate.api.ToolGate#pending(Principal)}
     */
    public List<ReviewItem> pending(Principal viewer) {
        return gating.gate().pending(viewer);
    }

    /**
     * Approves a deferred call with no comment; see {@link #approve(String, Principal, Principal, String)}.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @return ToolGate's outcome
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal) {
        return approve(approvalId, reviewer, currentPrincipal, null);
    }

    /**
     * Approves a deferred call and, if ToolGate releases it, claims and runs it here, reporting the
     * outcome to the queue. The tool is found before approving: when this instance cannot run it,
     * nothing is approved and the call stays pending.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @param comment          the reviewer's justification; required of a super admin
     * @return ToolGate's outcome; on {@link Approval.Released} the call ran, here or wherever it was
     *         claimed first; its status says how it ended
     * @throws IllegalArgumentException if no call has that ID, or the principal is not the call's
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal, String comment) {
        return approve(approvalId, reviewer, currentPrincipal, comment, null);
    }

    /**
     * Approves a deferred call, as {@link #approve(String, Principal, Principal, String)} does, only if
     * the review screen showed exactly what would be approved; a review screen always passes the digest.
     *
     * @param approvalId       the pending call
     * @param reviewer         who approves
     * @param currentPrincipal the call's principal as resolved now, so revoked roles count
     * @param comment          the reviewer's justification; required of a super admin
     * @param digest           {@link ReviewItem#digest()} of what the reviewer saw; null to skip the check
     * @return ToolGate's outcome
     */
    public Approval approve(String approvalId, Principal reviewer, Principal currentPrincipal, String comment,
                            String digest) {
        Optional<PendingCall> waiting = gating.gate().pending().stream()
                .filter(pending -> pending.approvalId().equals(approvalId)).findFirst();
        if (waiting.isEmpty()) {
            // Decided, expired or unknown: ToolGate says which, and nothing will run.
            gating.forget(approvalId);
            return gating.gate().approve(approvalId, reviewer, currentPrincipal, comment, digest);
        }
        Optional<Gating.Kept> tool = runnable(waiting.get());
        if (tool.isEmpty()) {
            return new Approval.NotPermitted(waiting.get().call().tool() + " cannot run on this instance: it came from a"
                    + " tool provider on another instance, or that instance restarted; register the tool with"
                    + " agent(...).tools(...)");
        }
        Approval approval = gating.gate().approve(approvalId, reviewer, currentPrincipal, comment, digest);
        if (approval instanceof Approval.Released) {
            execute(approvalId, tool.get());
        }
        return approval;
    }

    /**
     * One deferred call as a viewer may see it on a review screen; see {@link io.toolgate.api.ToolGate#reviewItem}.
     *
     * @param approvalId the call
     * @param viewer     who is looking
     * @return the item, or empty if the viewer may not see it
     */
    public Optional<ReviewItem> reviewItem(String approvalId, Principal viewer) {
        return gating.gate().reviewItem(approvalId, viewer);
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

    /**
     * Withdraws a deferred call nobody decided yet; see {@link io.toolgate.api.ToolGate#cancel}.
     *
     * @param approvalId the pending call
     * @param by         its requester, or a super admin
     * @param reason     why, for the audit trail
     * @return {@code true} if the call was pending and is now cancelled
     */
    public boolean cancel(String approvalId, Principal by, String reason) {
        boolean cancelled = gating.gate().cancel(approvalId, by, reason);
        if (cancelled) {
            gating.forget(approvalId);
        }
        return cancelled;
    }

    /**
     * Records how a run with an unknown outcome really ended; see {@link io.toolgate.api.ToolGate#resolve}.
     *
     * @param approvalId the call
     * @param by         a reviewer of its tenant, or a super admin
     * @param ran        whether the tool did run
     * @param reason     what they found
     * @return {@code true} if resolved
     */
    public boolean resolve(String approvalId, Principal by, boolean ran, String reason) {
        return gating.gate().resolve(approvalId, by, ran, reason);
    }

    /**
     * Keeps the approval queue moving: sweeps it, then runs the released calls this instance can run that
     * nobody claimed yet, such as calls approved from another process. Call it on a schedule, such as
     * every 30 seconds; claiming is compare-and-set, so across instances each call runs once. A tool
     * that fails is recorded as {@link ApprovalStatus#FAILED} and the others still run.
     *
     * @return how many calls this instance ran, whether they succeeded or failed
     */
    public int runApproved() {
        gating.gate().sweep();
        int ran = 0;
        for (PendingCall released : gating.gate().withStatus(ApprovalStatus.RELEASED)) {
            Optional<Gating.Kept> tool = runnable(released);
            if (tool.isEmpty()) {
                continue;
            }
            try {
                ran += execute(released.approvalId(), tool.get()) ? 1 : 0;
            } catch (RuntimeException failed) {
                // Already recorded as FAILED, with the error, by execute.
                ran++;
            }
        }
        return ran;
    }

    /** The tool a call needs, if this instance has it: kept from its request, or registered with an agent. */
    private Optional<Gating.Kept> runnable(PendingCall call) {
        ToolCall deferred = call.call();
        Optional<Gating.Kept> tool = gating.kept(call.approvalId()).or(() -> gating.registered(deferred.tool())
                .map(executor -> new Gating.Kept(executor, ArgValueJson.write(deferred.args()))));
        // An instance started after the deferral may not have seen the tool offered yet; the re-check needs it.
        tool.ifPresent(found -> gating.definition(deferred.tool(), found.executor().specification()));
        return tool;
    }

    /** Claims a released call and runs it, reporting the outcome; nothing runs if another executor claimed it. */
    private boolean execute(String approvalId, Gating.Kept tool) {
        Optional<Approval.Released> claimed = gating.gate().claim(approvalId, executorId, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        gating.forget(approvalId);
        GatedToolExecutor executor = tool.executor();
        ToolCall call = claimed.get().call();
        var request = ToolExecutionRequest.builder().id(call.callId()).name(executor.specification().name())
                .arguments(tool.input()).build();
        var context = InvocationContext.builder().chatMemoryId(call.sessionId())
                .invocationParameters(InvocationParameters.from(Map.of(
                        ToolGateParameters.PRINCIPAL, call.principal(),
                        ToolGateParameters.SESSION, call.sessionId(),
                        ToolGateParameters.IDEMPOTENCY_KEY, claimed.get().idempotencyKey())))
                .build();
        try {
            ToolExecutionResult result = executor.run(executor.target(), call, request, context);
            gating.gate().finished(approvalId, executorId, result.isError()
                    ? new ToolResult.Failed(String.valueOf(result.resultText()))
                    : new ToolResult.Succeeded(ArgValue.text(String.valueOf(result.resultText()))));
        } catch (RuntimeException failure) {
            gating.gate().finished(approvalId, executorId, new ToolResult.Failed(failure.toString()));
            throw failure;
        }
        return true;
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
        private String executorId;
        private Duration lease = Duration.ofMinutes(5);

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
         * Names this instance in the approval queue, and how long a run of an approved call may take
         * before its outcome counts as lost and the call becomes {@link ApprovalStatus#UNKNOWN}. By
         * default the host name and process ID, and 5 minutes.
         *
         * @param id        this instance's name
         * @param runLease  the lease
         * @return this builder
         */
        public Builder executor(String id, Duration runLease) {
            if (id == null || id.isBlank() || runLease.isNegative() || runLease.isZero()) {
                throw new IllegalArgumentException("executor needs a name and a positive lease");
            }
            this.executorId = id;
            this.lease = runLease;
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
            return new ToolGateLangChain4j(new Gating(built, catalog, clock, policy),
                    executorId != null ? executorId : defaultExecutorId(), lease);
        }

        private static String defaultExecutorId() {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException unknown) {
                host = "unknown-host";
            }
            return host + "-" + ProcessHandle.current().pid();
        }
    }
}
