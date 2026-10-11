package io.toolgate.demo;

import io.toolgate.springai.ToolGateContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** One ticket of Case E's nightly triage batch: one ticket, one session, nobody watching. */
@Service
public class TicketJob {

    private final ChatClient chatClient;
    private final TicketTools tools;

    TicketJob(ChatClient.Builder chatClients, TicketTools tools) {
        this.chatClient = chatClients
                .defaultSystem("You triage support tickets for mycompany.com. Read the ticket, tag it, and answer the"
                        + " customer when the help center or their account explains the issue.")
                .build();
        this.tools = tools;
    }

    /**
     * Triages one ticket.
     *
     * @param ticketId the ticket
     * @return the agent's final answer
     */
    public String triage(String ticketId) {
        String session = "ticket-" + ticketId + "-" + UUID.randomUUID();
        return chatClient.prompt().user("Triage ticket " + ticketId).tools(tools)
                .toolContext(Map.of(ToolGateContext.AGENT, "ticket-triage", ToolGateContext.SESSION, session))
                .call().content();
    }
}
