package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.AsyncNotSupportedException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Wraps a tool's executor and gates every way LangChain4j runs it: {@code execute},
 * {@code executeWithContext} and {@code executeAsync}. It applies the decision ToolGate's hook made
 * for the same request; when there is none, because the application's hook replaced ToolGate's or
 * LangChain4j ran the tool without the hook, it checks the call itself. From here a Terminate can only
 * deny: LangChain4j turns an executor's exception into text for the model.
 */
final class GatedToolExecutor implements ToolExecutor {

    private static final System.Logger LOG = System.getLogger("toolgate.adapter");

    private final ToolExecutor target;
    private final ToolSpecification specification;
    private final ToolId toolId;
    private final AgentTools agent;
    private final Gating gating;

    GatedToolExecutor(ToolExecutor target, ToolSpecification specification, ToolId toolId, AgentTools agent, Gating gating) {
        this.target = target;
        this.specification = specification;
        this.toolId = toolId;
        this.agent = agent;
        this.gating = gating;
    }

    ToolId toolId() {
        return toolId;
    }

    ToolSpecification specification() {
        return specification;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        if (gating.mode() == Mode.OFF) {
            // The emergency switch: tools run straight through, as if ToolGate were not there.
            return target.executeWithContext(request, forTarget(target, context));
        }
        Optional<Gating.Checked> checked = decided(request, context);
        if (checked.isEmpty()) {
            return target.executeWithContext(request, forTarget(target, context));
        }
        return apply(checked.get(), request, context);
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request, InvocationContext context) {
        if (gating.mode() == Mode.OFF) {
            return target.executeAsync(request, forTarget(target, context));
        }
        Optional<Gating.Checked> checked;
        try {
            checked = decided(request, context);
        } catch (RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        if (checked.isEmpty()) {
            return target.executeAsync(request, forTarget(target, context));
        }
        Gating.Checked call = checked.get();
        return switch (call.decision()) {
            case Decision.Allow allow -> runAsync(target, call.call(), request, context);
            case Decision.Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) -> agent.executor(safeTool)
                    .map(safe -> runAsync(safe.target, call.call().withTool(safeTool, safeArgs),
                            safe.request(request, safeInput(request.arguments(), call.arguments(), safeArgs)), context))
                    .orElseGet(() -> CompletableFuture.completedFuture(text(Decision.Deny.MODEL_MESSAGE)));
            default -> CompletableFuture.completedFuture(apply(call, request, context));
        };
    }

    /**
     * The hook's decision for this request, else one made here. Empty only in monitor mode when the
     * call cannot be built, where the tool runs unchecked with a warning.
     */
    private Optional<Gating.Checked> decided(ToolExecutionRequest request, InvocationContext context) {
        Optional<Gating.Checked> handedOver = gating.takeHandedOver(request);
        // The hook finds executors by name; a provider offering a tool of the same name from another server to a
        // concurrent request can make it decide for that tool. Only a decision for this very tool applies here.
        if (handedOver.isPresent() && handedOver.get().call().tool().equals(toolId)) {
            return handedOver;
        }
        try {
            return Optional.of(agent.check(this, request, context));
        } catch (ToolGateViolationException noContext) {
            if (gating.mode() == Mode.MONITOR) {
                LOG.log(System.Logger.Level.WARNING, "monitor mode: {0} runs unchecked: {1}", toolId, noContext.getMessage());
                return Optional.empty();
            }
            // Thrown from an executor, this becomes text for the model, and the tool does not run.
            throw noContext;
        }
    }

    private ToolExecutionResult apply(Gating.Checked checked, ToolExecutionRequest request, InvocationContext context) {
        return switch (checked.decision()) {
            case Decision.Allow allow -> run(target, checked.call(), request, context);
            case Decision.Deny deny -> text(Decision.Deny.MODEL_MESSAGE);
            case Decision.Defer defer -> {
                gating.keep(defer.approvalId(), this, request.arguments());
                yield text(Decision.Defer.MODEL_MESSAGE);
            }
            case Decision.Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) -> agent.executor(safeTool)
                    .map(safe -> run(safe.target, checked.call().withTool(safeTool, safeArgs),
                            safe.request(request, safeInput(request.arguments(), checked.arguments(), safeArgs)), context))
                    // The policy names a safe tool the agent does not have; fail closed.
                    .orElseGet(() -> text(Decision.Deny.MODEL_MESSAGE));
            case Decision.Terminate(String ruleId, String reason) -> {
                // Only a check made here gets a Terminate: the hook throws it. From an executor it can only deny.
                LOG.log(System.Logger.Level.WARNING, "{0} denied instead of ending the run, which ToolGate''s hook"
                        + " would have done: {1}", toolId, reason);
                yield text(Decision.Deny.MODEL_MESSAGE);
            }
        };
    }

    /** Runs a tool, records what it returned, and gives back the result unless a result rule withholds it. */
    ToolExecutionResult run(ToolExecutor tool, ToolCall callThatRuns, ToolExecutionRequest request, InvocationContext context) {
        ToolExecutionResult result;
        try {
            result = tool.executeWithContext(request, forTarget(tool, context));
        } catch (RuntimeException failure) {
            // An error message can carry an attacker's page as easily as a result, so it taints too.
            gating.gate().recordResult(callThatRuns, new ToolResult.Failed(describe(failure)));
            throw failure;
        }
        return recorded(callThatRuns, result);
    }

    private CompletableFuture<ToolExecutionResult> runAsync(ToolExecutor tool, ToolCall callThatRuns,
                                                            ToolExecutionRequest request, InvocationContext context) {
        InvocationContext forTool = forTarget(tool, context);
        CompletableFuture<ToolExecutionResult> running;
        try {
            running = tool.executeAsync(request, forTool).exceptionallyCompose(error -> {
                // A tool without async support runs here instead; LangChain4j would otherwise retry it through
                // this executor, which has already used its decision.
                Throwable cause = error instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : error;
                return cause instanceof AsyncNotSupportedException
                        ? CompletableFuture.completedFuture(tool.executeWithContext(request, forTool))
                        : CompletableFuture.failedFuture(cause);
            });
        } catch (AsyncNotSupportedException notAsync) {
            running = CompletableFuture.supplyAsync(() -> tool.executeWithContext(request, forTool));
        } catch (RuntimeException failure) {
            running = CompletableFuture.failedFuture(failure);
        }
        return running.handle((result, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : error;
                gating.gate().recordResult(callThatRuns, new ToolResult.Failed(describe(cause)));
                throw cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
            }
            return recorded(callThatRuns, result);
        });
    }

    private ToolExecutionResult recorded(ToolCall callThatRan, ToolExecutionResult result) {
        String shown = result == null || result.resultText() == null ? "" : result.resultText();
        ToolResult recorded = result != null && result.isError()
                ? new ToolResult.Failed(shown)
                : new ToolResult.Succeeded(value(shown));
        return switch (gating.gate().recordResult(callThatRan, recorded)) {
            case ResultDecision.Release release -> result == null ? text("") : result;
            case ResultDecision.Withhold withhold -> text(ResultDecision.Withhold.MODEL_MESSAGE);
        };
    }

    /** A request for this executor's tool, with the given arguments, keeping the model's call ID. */
    ToolExecutionRequest request(ToolExecutionRequest original, String arguments) {
        return ToolExecutionRequest.builder().id(original.id()).name(specification.name()).arguments(arguments).build();
    }

    /** The executor this one wraps, for running an approved call. */
    ToolExecutor target() {
        return target;
    }

    /**
     * The safe tool gets the model's own text when its arguments are unchanged, the usual case, since
     * re-writing them would turn 480 into another spelling for a tool that reads it as text.
     */
    private static String safeInput(String modelInput, ObjectValue arguments, ObjectValue safeArgs) {
        if (!safeArgs.equals(arguments)) {
            return ArgValueJson.write(safeArgs);
        }
        return modelInput == null || modelInput.isBlank() ? "{}" : modelInput;
    }

    /**
     * The context a tool may see. An MCP client may send invocation parameters to its server; the
     * principal and session are no business of a third party.
     */
    static InvocationContext forTarget(ToolExecutor tool, InvocationContext context) {
        if (context == null || context.invocationParameters() == null || !McpTools.isMcp(tool)) {
            return context;
        }
        var kept = new HashMap<String, Object>(context.invocationParameters().asMap());
        kept.keySet().removeIf(ToolGateParameters::isToolGateKey);
        return context.toBuilder().invocationParameters(InvocationParameters.from(kept)).build();
    }

    private static ToolExecutionResult text(String text) {
        return ToolExecutionResult.builder().resultText(text).build();
    }

    private static ArgValue value(String output) {
        try {
            return ArgValueJson.read(output);
        } catch (IllegalArgumentException notJson) {
            return ArgValue.text(output);
        }
    }

    /** A failure's message for the record; recording must never fail on an exception without one. */
    static String describe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                return cause.getMessage();
            }
        }
        return failure.getClass().getName();
    }
}
