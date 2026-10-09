package io.toolgate.spike;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * The design's hook: a manager bean that wraps every callback of the request, and every callback the
 * resolver finds, then delegates to Spring AI's own manager. Where ToolGate would call check(), it
 * records the call.
 */
final class RecordingToolCallingManager implements ToolCallingManager {

    /** A tool context key that makes the wrapper end the run, as it would on a Terminate decision. */
    static final String TERMINATE = "spike.terminate";

    private final ToolCallingManager delegate;

    RecordingToolCallingManager(ToolCallbackResolver resolver) {
        this(resolver, false);
    }

    /**
     * @param resolutionFallback whether tools the request did not offer are looked up by name, as
     *                           before the CVE-2026-59318 fix and still configurable
     */
    RecordingToolCallingManager(ToolCallbackResolver resolver, boolean resolutionFallback) {
        this.delegate = DefaultToolCallingManager.builder()
                .toolCallbackResolver(name -> {
                    ToolCallback found = resolver.resolve(name);
                    return found == null ? null : new Recording(found);
                })
                .resolutionFallbackEnabled(resolutionFallback)
                .build();
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Prompt gated = prompt;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            List<ToolCallback> wrapped = options.getToolCallbacks().stream()
                    .map(callback -> (ToolCallback) new Recording(callback))
                    .toList();
            gated = new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(wrapped).build());
        }
        return delegate.executeToolCalls(gated, chatResponse);
    }

    /** Stands where GatedToolCallback would: sees the name, raw arguments and tool context. */
    static final class Recording implements ToolCallback {

        private final ToolCallback target;

        Recording(ToolCallback target) {
            this.target = target;
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
            Map<String, Object> context = toolContext == null ? Map.of() : Map.copyOf(toolContext.getContext());
            Recorder.SEEN.add(new Recorder.Seen(getToolDefinition().name(), toolInput, context,
                    Thread.currentThread().getName(), target.getClass().getSimpleName()));
            if (context.containsKey(TERMINATE)) {
                throw new IllegalStateException("run ended by the gate");
            }
            return target.call(toolInput, toolContext);
        }
    }
}
