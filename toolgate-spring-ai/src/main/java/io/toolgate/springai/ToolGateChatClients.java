package io.toolgate.springai;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Builds gated chat clients by hand. {@code ChatClient.create(model)} and {@code ChatClient.builder(model)}
 * give a client a tool manager of its own, which ToolGate never sees; use this instead, or inject
 * Spring Boot's {@code ChatClient.Builder}, which is gated already.
 *
 * <pre>{@code
 * ChatClient client = toolGateChatClients.builder(chatModel).build();
 * }</pre>
 */
public final class ToolGateChatClients {

    private final ToolCallingManager gatedManager;
    private final Supplier<Gating> gating;

    ToolGateChatClients(ToolCallingManager gatedManager, Supplier<Gating> gating) {
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
                        ToolCallingAdvisor.builder().toolCallingManager(gatedManager))
                .defaultAdvisors(new ToolGateAdvisor(gating));
    }
}
