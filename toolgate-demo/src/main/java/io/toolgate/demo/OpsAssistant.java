package io.toolgate.demo;

import io.toolgate.api.Principal;
import io.toolgate.springai.ToolGateContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** Case C's internal assistant: engineers ask about production, and it reads logs and status pages. */
@Service
public class OpsAssistant {

    private final ChatClient chatClient;
    private final OpsTools tools;

    OpsAssistant(ChatClient.Builder chatClients, OpsTools tools) {
        this.chatClient = chatClients.defaultSystem("You help engineers diagnose production issues.").build();
        this.tools = tools;
    }

    /**
     * Answers one question.
     *
     * @param engineer     who is asking, from single sign-on
     * @param conversation the conversation, one session
     * @param question     what the engineer asks
     * @return the assistant's answer
     */
    public String ask(Principal engineer, String conversation, String question) {
        return chatClient.prompt().user(question).tools(tools)
                .toolContext(ToolGateContext.of("ops-assistant", engineer, conversation))
                .call().content();
    }
}
