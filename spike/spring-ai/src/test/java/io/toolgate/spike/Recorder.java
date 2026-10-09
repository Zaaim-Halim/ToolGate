package io.toolgate.spike;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the spike observes: every call the wrapper saw, and every tool body that actually ran. A path
 * is covered when everything that ran was also seen.
 */
final class Recorder {

    /**
     * A call the gate's wrapper saw, with what it would hand to ToolGate.
     *
     * @param source the wrapped callback's class, which tells a local tool from an MCP one
     */
    record Seen(String tool, String arguments, Map<String, Object> toolContext, String thread, String source) {}

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    static final List<String> RAN = new CopyOnWriteArrayList<>();
    /** What an MCP-served tool received as its tool context, on the server side. */
    static final List<String> SERVER_SIDE_CONTEXT = new CopyOnWriteArrayList<>();
    /** Bodies of MCP tools/call requests as they arrived over HTTP. */
    static final List<String> MCP_WIRE = new CopyOnWriteArrayList<>();

    private Recorder() {}

    static void reset() {
        SEEN.clear();
        RAN.clear();
        SERVER_SIDE_CONTEXT.clear();
        MCP_WIRE.clear();
    }

    static List<String> seenTools() {
        return SEEN.stream().map(Seen::tool).toList();
    }
}
