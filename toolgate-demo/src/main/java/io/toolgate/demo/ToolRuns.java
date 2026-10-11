package io.toolgate.demo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** What the demo's tools actually ran, with their arguments, as "tool argument": what ToolGate let through. */
@Component
public class ToolRuns {

    private final List<String> runs = new CopyOnWriteArrayList<>();

    void ran(String tool, Object... arguments) {
        var entry = new StringBuilder(tool);
        for (Object argument : arguments) {
            entry.append(' ').append(argument);
        }
        runs.add(entry.toString());
    }

    /** @return every run so far, in order */
    public List<String> all() {
        return List.copyOf(runs);
    }

    /** Forgets the runs, between scenarios. */
    public void clear() {
        runs.clear();
    }
}
