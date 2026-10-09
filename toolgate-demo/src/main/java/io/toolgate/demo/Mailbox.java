package io.toolgate.demo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Stands in for a mail server: what the agent sent, and what it left as drafts for a reviewer. */
@Component
public class Mailbox {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> drafts = new CopyOnWriteArrayList<>();

    /** @return recipients of sent emails, in order */
    public List<String> sent() {
        return List.copyOf(sent);
    }

    /** @return recipients of drafts, in order */
    public List<String> drafts() {
        return List.copyOf(drafts);
    }

    void send(String to) {
        sent.add(to);
    }

    void draft(String to) {
        drafts.add(to);
    }

    /** Empties both, between runs. */
    public void clear() {
        sent.clear();
        drafts.clear();
    }
}
