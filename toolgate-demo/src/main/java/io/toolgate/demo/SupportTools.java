package io.toolgate.demo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Case B's and Case G's tools: the invoice tool returns any invoice it is asked for, whichever tenant
 * owns it, as many tools do; the email tool sends wherever it is told.
 */
@Component
public class SupportTools {

    private final Mailbox mailbox;
    private final ToolRuns runs;

    SupportTools(Mailbox mailbox, ToolRuns runs) {
        this.mailbox = mailbox;
        this.runs = runs;
    }

    @Tool(description = "Get an invoice by its ID")
    String getInvoice(String id) {
        runs.ran("getInvoice", id);
        return "{\"id\":\"" + id + "\",\"tenant\":\"" + InvoiceTools.TENANT_OF_INVOICE.getOrDefault(id, "unknown")
                + "\",\"amount\":\"1200.00\"}";
    }

    @Tool(description = "Send an email")
    String sendEmail(String to, String body) {
        runs.ran("sendEmail", to);
        mailbox.send(to);
        return "sent to " + to;
    }
}
