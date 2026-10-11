package io.toolgate.demo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** An email assistant's tools. Messages come from anyone; contacts and calendar are the user's own. */
@Component
public class InboxTools {

    private final Mailbox mailbox;

    InboxTools(Mailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Tool(description = "List the newest messages in the user's inbox")
    String listInbox() {
        return "[{\"id\":\"M-1\",\"from\":\"jo@partner.example\",\"subject\":\"Meeting next week?\"}]";
    }

    @Tool(description = "Read one message by its ID")
    String readMessage(String id) {
        return "{\"id\":\"" + id + "\",\"from\":\"jo@partner.example\",\"body\":\"Could we meet Tuesday at 10?\"}";
    }

    @Tool(description = "Search the user's contacts by name")
    String searchContacts(String name) {
        return "[{\"name\":\"Jo Park\",\"email\":\"jo@partner.example\",\"phone\":\"+49 30 1234567\"}]";
    }

    @Tool(description = "Read the user's calendar for a day")
    String readCalendar(String date) {
        return "[{\"time\":\"09:00\",\"title\":\"Board prep\"},{\"time\":\"14:00\",\"title\":\"1:1 with Sam\"}]";
    }

    @Tool(description = "Add an event to the user's calendar")
    String createEvent(String title, String start) {
        return "created " + title + " at " + start;
    }

    @Tool(description = "Send an email from the user's account")
    String sendMessage(String to, String body) {
        mailbox.send(to);
        return "sent to " + to;
    }
}
