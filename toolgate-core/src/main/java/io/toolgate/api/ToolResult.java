package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Objects;

/**
 * What a tool returned to the model. Either way the model read it, so either way the tool's source
 * labels taint the session: an error message can quote an attacker's page as easily as a result.
 */
public sealed interface ToolResult permits ToolResult.Succeeded, ToolResult.Failed {

    /**
     * The tool returned normally.
     *
     * @param output what it returned; {@link NullValue} for a tool that returns nothing
     */
    record Succeeded(ArgValue output) implements ToolResult {
        /** Requires the output. */
        public Succeeded {
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * The tool failed, and the model was shown an error.
     *
     * @param message the error the model saw
     */
    record Failed(String message) implements ToolResult {
        /** Requires the message. */
        public Failed {
            requireNonBlank(message, "message");
        }
    }
}
