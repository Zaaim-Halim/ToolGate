package io.toolgate.demo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Case B's tool: it returns any invoice it is asked for, whichever tenant owns it, as many tools do. */
@Component
public class SupportTools {

    @Tool(description = "Get an invoice by its ID")
    String getInvoice(String id) {
        return "{\"id\":\"" + id + "\",\"tenant\":\"" + InvoiceTools.TENANT_OF_INVOICE.getOrDefault(id, "unknown")
                + "\",\"amount\":\"1200.00\"}";
    }
}
