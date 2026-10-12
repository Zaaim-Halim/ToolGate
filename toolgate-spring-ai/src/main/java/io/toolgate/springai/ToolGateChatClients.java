package io.toolgate.springai;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Builds gated chat clients by hand. {@code ChatClient.create(model)} and {@code ChatClient.builder(model)}
 * give a client a tool manager of its own, so ToolGate refuses their requests that offer tools; use this
 * instead, or inject Spring Boot's {@code ChatClient.Builder}, which is gated already.
 *
 * <pre>{@code
 * ChatClient client = toolGateChatClients.builder(chatModel).build();
 * }</pre>
 */
public final class ToolGateChatClients {

    private final Supplier<ToolCallingManager> gatedManager;
    private final Supplier<Gating> gating;

    ToolGateChatClients(Supplier<ToolCallingManager> gatedManager, Supplier<Gating> gating) {
        this.gatedManager = gatedManager;
        this.gating = gating;
    }

    /**
     * A chat client builder whose tool calls are checked by ToolGate.
     *
     * @param chatModel the model
     * @return a builder, as {@code ChatClient.builder(chatModel)} would return
     */
    public ChatClient.Builder builder(ChatModel chatModel) {
        return ChatClient.builder(chatModel, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(gatedManager.get()))
                .defaultAdvisors(new ToolGateAdvisor(gating));
    }

    /**
     * A prompt whose tool callbacks are checked by ToolGate, for an application that calls the chat model
     * itself and runs the model's tool calls with Spring's {@code ToolCallingManager} bean. The model
     * refuses tool callbacks ToolGate did not wrap, since another client would run them unchecked.
     *
     * <pre>{@code
     * ChatResponse response = chatModel.call(toolGateChatClients.gated(prompt));
     * }</pre>
     *
     * @param prompt the prompt, with its tool callbacks and tool context
     * @return the same prompt with every tool callback gated
     */
    public Prompt gated(Prompt prompt) {
        return GatedToolCallingManager.gated(prompt, gating.get());
    }
}
