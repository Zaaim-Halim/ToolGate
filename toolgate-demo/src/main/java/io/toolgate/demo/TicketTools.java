package io.toolgate.demo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Case E's ticket tools. Ticket text is written by customers, so anything in it may be an instruction. */
@Component
public class TicketTools {

    private final Mailbox mailbox;

    TicketTools(Mailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Tool(description = "Read a support ticket by its number")
    String readTicket(String id) {
        return "{\"id\":\"" + id + "\",\"customer\":\"C-5512\",\"body\":\"My invoice shows the wrong VAT rate.\"}";
    }

    @Tool(description = "Search the help center articles")
    String searchKnowledgeBase(String query) {
        return "[{\"title\":\"How VAT is applied to invoices\",\"url\":\"https://help.mycompany.com/vat\"}]";
    }

    @Tool(description = "Look up a customer's account by ID")
    String lookupCustomer(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Maria Lopez\",\"email\":\"maria@acme.example\",\"plan\":\"business\"}";
    }

    @Tool(description = "Add a tag to a ticket, such as billing or urgent")
    String tagTicket(String id, String tag) {
        return "tagged " + id + " " + tag;
    }

    @Tool(description = "Send a reply to a customer")
    String replyToCustomer(String customerId, String body) {
        mailbox.send("customer:" + customerId);
        return "reply sent to " + customerId;
    }

    @Tool(description = "Save a reply to a customer as a draft for a support agent")
    String draftReply(String customerId, String body) {
        mailbox.draft("customer:" + customerId);
        return "draft saved for " + customerId;
    }
}
