package io.toolgate.spike;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the spike observes: every request ToolGate's hook and wrapped executors saw, and every tool body
 * that actually ran. A path is covered when everything that ran was seen by both.
 */
final class Recorder {

    /**
     * @param via        {@code hook}, or the executor method that ran: {@code execute},
     *                   {@code executeWithContext} or {@code executeAsync}
     * @param callId     the model's tool call ID, which LangChain4j hands to both
     * @param parameters the invocation parameters, where the principal and session travel
     */
    record Seen(String via, String tool, String arguments, String callId, Map<String, Object> parameters, String thread) {}

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    static final List<String> RAN = new CopyOnWriteArrayList<>();

    private Recorder() {}

    static void reset() {
        SEEN.clear();
        RAN.clear();
    }

    static List<String> seen(String via) {
        return SEEN.stream().filter(seen -> seen.via().equals(via)).map(Seen::tool).toList();
    }

    static List<String> seenByExecutors() {
        return SEEN.stream().filter(seen -> !seen.via().equals("hook")).map(Seen::tool).toList();
    }
}
