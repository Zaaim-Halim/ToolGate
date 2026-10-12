package io.toolgate.springai;

import io.toolgate.api.Mode;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps the application's own manager, so its limits, error handling and observation stay as
 * configured, and gates every callback of each request before that manager runs it.
 *
 * <p>A chat model asks this manager for the definitions of the tools it offers the model, on every
 * request, whichever chat client sent it. A request whose callbacks ToolGate did not wrap comes from a
 * client that would run them unchecked, such as one built with {@code ChatClient.create(model)}: it is
 * refused before the model sees any tool (enforce), or logged (monitor).
 */
final class GatedToolCallingManager implements ToolCallingManager {

    /** The rule of a request refused because its tools would run without ToolGate. */
    static final String UNGATED = "adapter.ungated";

    private static final System.Logger LOG = System.getLogger("toolgate.adapter");

    private final ToolCallingManager delegate;
    private final Supplier<Gating> gating;

    GatedToolCallingManager(ToolCallingManager delegate, Supplier<Gating> gating) {
        this.delegate = delegate;
        this.gating = gating;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        refuseUngated(chatOptions.getToolCallbacks());
        return delegate.resolveToolDefinitions(chatOptions);
    }

    private void refuseUngated(List<ToolCallback> callbacks) {
        if (callbacks == null) {
            return;
        }
        List<String> ungated = callbacks.stream().filter(callback -> !(callback instanceof GatedToolCallback))
                .map(callback -> callback.getToolDefinition().name()).toList();
        if (ungated.isEmpty()) {
            return;
        }
        Mode mode = gating.get().mode();
        String fix = "build the ChatClient from Spring Boot's ChatClient.Builder or ToolGateChatClients, or pass a"
                + " prompt for the chat model through ToolGateChatClients.gated";
        switch (mode) {
            case OFF -> { }
            case MONITOR -> LOG.log(System.Logger.Level.WARNING, "monitor mode: tools {0} were offered to the model"
                    + " by a client that runs them without ToolGate; {1}", ungated, fix);
            case ENFORCE -> {
                LOG.log(System.Logger.Level.WARNING, "refused a model request: tools {0} would run without ToolGate;"
                        + " {1}", ungated, fix);
                throw new ToolGateViolationException(UNGATED, "tools " + ungated + " would run without ToolGate; " + fix);
            }
        }
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
