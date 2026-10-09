package io.toolgate.springai;

import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Gates a request's tool callbacks before any tool advisor sees them, so even an application's own
 * {@code ToolAdvisor}, with a manager of its own, runs gated callbacks; and completes the request's
 * tool context with the principal, agent and session, on the request's own thread.
 */
final class ToolGateAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * First of all, so that a tool advisor of the application's, at any order of its own, takes gated
     * callbacks; Spring AI's own runs at {@link ToolCallingAdvisor#DEFAULT_ORDER}. An advisor at this same
     * order could still come first; that tie is a documented limit.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final Supplier<Gating> gating;

    ToolGateAdvisor(Supplier<Gating> gating) {
        this.gating = gating;
    }

    @Override
    public String getName() {
        return "toolgate";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(gated(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(gated(request));
    }

    private ChatClientRequest gated(ChatClientRequest request) {
        Gating shared = gating.get();
        Prompt prompt = request.prompt();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            prompt = new Prompt(prompt.getInstructions(),
                    options.mutate().toolContext(shared.completeContext(options.getToolContext(), request.context())).build());
        }
        return request.mutate().prompt(GatedToolCallingManager.gated(prompt, shared)).build();
    }
}
