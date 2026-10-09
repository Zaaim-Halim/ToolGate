package io.toolgate.springai;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps the application's own manager, so its limits, error handling and observation stay as
 * configured, and gates every callback of each request before that manager runs it.
 */
final class GatedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final Supplier<Gating> gating;

    GatedToolCallingManager(ToolCallingManager delegate, Supplier<Gating> gating) {
        this.delegate = delegate;
        this.gating = gating;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        return delegate.executeToolCalls(gated(prompt, gating.get()), chatResponse);
    }

    /** The prompt with every tool callback in its options gated. */
    static Prompt gated(Prompt prompt, Gating gating) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                && !options.getToolCallbacks().isEmpty()) {
            return new Prompt(prompt.getInstructions(),
                    options.mutate().toolCallbacks(gating.wrap(options.getToolCallbacks())).build());
        }
        return prompt;
    }
}
