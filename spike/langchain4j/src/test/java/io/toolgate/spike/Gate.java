package io.toolgate.spike;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The design's LangChain4j hook, recording where ToolGate would call check(): executors wrapped and
 * registered through ToolGate, and a beforeToolExecution hook that fails closed on any tool that was
 * not, and ends the run on Terminate.
 */
final class Gate {

    /** An invocation parameter that makes the hook end the run, as on a Terminate decision. */
    static final String TERMINATE = "spike.terminate";
    /** An invocation parameter that makes the wrapped executor throw, as a deny might be tried. */
    static final String THROW_IN_EXECUTOR = "spike.throwInExecutor";

    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /** Wraps tools, as ToolGateTools.register would, and remembers their names. */
    List<AiServiceTool> wrap(List<AiServiceTool> tools) {
        return tools.stream().map(this::wrap).toList();
    }

    /** Wraps every tool a provider returns, per request. */
    ToolProvider wrap(ToolProvider provider) {
        return new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(dev.langchain4j.service.tool.ToolProviderRequest request) {
                ToolProviderResult result = provider.provideTools(request);
                return ToolProviderResult.builder().addAll(wrap(result.aiServiceTools())).build();
            }

            @Override
            public boolean isDynamic() {
                return provider.isDynamic();
            }
        };
    }

    private AiServiceTool wrap(AiServiceTool tool) {
        registered.add(tool.name());
        return AiServiceTool.builder()
                .toolSpecification(tool.toolSpecification())
                .toolExecutor(new Recording(tool.toolExecutor()))
                .returnBehavior(tool.returnBehavior())
                .build();
    }

    /**
     * ToolGate's hook, wrapping the application's own, since an AI service keeps a single one.
     *
     * @param applicationHook the hook the application wants too, or null
     */
    Consumer<BeforeToolExecution> hook(Consumer<BeforeToolExecution> applicationHook) {
        return before -> {
            ToolExecutionRequest request = before.request();
            Recorder.SEEN.add(seen("hook", request, before.invocationContext()));
            if (!registered.contains(request.name())) {
                throw new IllegalStateException("tool " + request.name() + " was not registered through ToolGate");
            }
            if (parameters(before.invocationContext()).containsKey(TERMINATE)) {
                throw new IllegalStateException("run ended by the gate");
            }
            if (applicationHook != null) {
                applicationHook.accept(before);
            }
        };
    }

    private static Recorder.Seen seen(String via, ToolExecutionRequest request, InvocationContext context) {
        return new Recorder.Seen(via, request.name(), request.arguments(), request.id(), parameters(context),
                Thread.currentThread().getName());
    }

    private static Map<String, Object> parameters(InvocationContext context) {
        return context == null || context.invocationParameters() == null
                ? Map.of()
                : Map.copyOf(context.invocationParameters().asMap());
    }

    /** Stands where GatedToolExecutor would; every way to run the tool goes through it. */
    private static final class Recording implements ToolExecutor {

        private final ToolExecutor target;

        Recording(ToolExecutor target) {
            this.target = target;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            Recorder.SEEN.add(seen("execute", request, null));
            return target.execute(request, memoryId);
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
            Recorder.SEEN.add(seen("executeWithContext", request, context));
            if (parameters(context).containsKey(THROW_IN_EXECUTOR)) {
                throw new IllegalStateException("thrown by the gate's executor");
            }
            return target.executeWithContext(request, context);
        }

        @Override
        public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request, InvocationContext context) {
            Recorder.SEEN.add(seen("executeAsync", request, context));
            return target.executeAsync(request, context);
        }
    }
}
