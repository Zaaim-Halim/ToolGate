package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;

/**
 * The call that first brought a label into a session, so a decision can name where its taint came from.
 *
 * @param callId the call
 * @param tool   the tool it ran
 */
public record TaintSource(String callId, ToolId tool) {

    /** Requires both parts. */
    public TaintSource {
        requireNonBlank(callId, "callId");
        Objects.requireNonNull(tool, "tool");
    }
}
