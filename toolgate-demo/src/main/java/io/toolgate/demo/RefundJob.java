package io.toolgate.demo;

import io.toolgate.springai.ToolGateContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** Case F's headless refund agent: one return request, one session, nobody watching. */
@Service
public class RefundJob {

    private final ChatClient chatClient;
    private final RefundTools tools;

    RefundJob(ChatClient.Builder chatClients, RefundTools tools) {
        this.chatClient = chatClients.defaultSystem("You handle return requests and issue the refunds they need.")
                .build();
        this.tools = tools;
    }

    /**
     * Handles one return request.
     *
     * @param request the customer's request
     * @return the agent's final answer
     */
    public String handle(String request) {
        String session = "refund-" + UUID.randomUUID();
        return chatClient.prompt().user(request).tools(tools)
                .toolContext(Map.of(ToolGateContext.AGENT, "refund-agent", ToolGateContext.SESSION, session))
                .call().content();
    }
}
