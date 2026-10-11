package io.toolgate.demo;

import io.toolgate.api.Principal;
import io.toolgate.springai.ToolGateContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** An interactive email assistant: the signed-in user asks, and confirms whatever the policy holds. */
@Service
public class InboxAssistant {

    private final ChatClient chatClient;
    private final InboxTools tools;

    InboxAssistant(ChatClient.Builder chatClients, InboxTools tools) {
        this.chatClient = chatClients
                .defaultSystem("You help the user with their email and calendar. Read what you need and act only on"
                        + " what the user asks.")
                .build();
        this.tools = tools;
    }

    /**
     * Answers one request from the user.
     *
     * @param user         who is asking, from the application's sign-in
     * @param conversation the conversation, one session
     * @param request      what the user asks
     * @return the assistant's answer
     */
    public String ask(Principal user, String conversation, String request) {
        return chatClient.prompt().user(request).tools(tools)
                .toolContext(ToolGateContext.of("inbox-assistant", user, conversation))
                .call().content();
    }
}
