package io.toolgate.demo;

import io.toolgate.springai.ToolGateContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** One run of the nightly invoice job: one invoice, one session, nobody watching. */
@Service
public class InvoiceJob {

    private final ChatClient chatClient;
    private final InvoiceTools tools;

    InvoiceJob(ChatClient.Builder chatClients, InvoiceTools tools) {
        this.chatClient = chatClients
                .defaultSystem("You process supplier invoices for the accounts team at mycompany.com. Read the invoice,"
                        + " check the supplier's payment terms, and email a summary to billing@mycompany.com.")
                .build();
        this.tools = tools;
    }

    /**
     * Processes one invoice.
     *
     * @param invoiceId the invoice
     * @return the agent's final answer
     */
    public String process(String invoiceId) {
        String session = "job-" + invoiceId + "-" + UUID.randomUUID();
        return chatClient.prompt().user("Process invoice " + invoiceId).tools(tools)
                // Who the job acts as is the policy's business: the agent declares its service identity.
                .toolContext(Map.of(ToolGateContext.AGENT, "invoice-processor", ToolGateContext.SESSION, session))
                .call().content();
    }
}
