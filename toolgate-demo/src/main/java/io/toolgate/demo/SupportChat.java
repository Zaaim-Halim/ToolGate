package io.toolgate.demo;

import io.toolgate.api.Principal;
import io.toolgate.springai.ToolGateContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** Case B's in-app assistant: the signed-in user asks about their company's invoices. */
@Service
public class SupportChat {

    private final ChatClient chatClient;
    private final SupportTools tools;

    SupportChat(ChatClient.Builder chatClients, SupportTools tools) {
        this.chatClient = chatClients.defaultSystem("You answer questions about the user's invoices.").build();
        this.tools = tools;
    }

    /**
     * Answers one question.
     *
     * @param user         who is asking, from the application's sign-in
     * @param conversation the conversation, one session
     * @param question     what the user asks
     * @return the assistant's answer
     */
    public String ask(Principal user, String conversation, String question) {
        return chatClient.prompt().user(question).tools(tools)
                .toolContext(ToolGateContext.of("support-chat", user, conversation))
                .call().content();
    }
}
