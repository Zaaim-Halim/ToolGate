package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;

/**
 * One argument of one tool, as an argument rule targets it.
 *
 * @param tool the tool
 * @param name the argument's name, as the tool declares it
 */
public record ToolArgument(ToolId tool, String name) {

    /** Requires both parts. */
    public ToolArgument {
        Objects.requireNonNull(tool, "tool");
        requireNonBlank(name, "argument name");
    }

    /**
     * Parses a target as written in a policy: a tool reference, a dot, and the argument's name.
     *
     * <p>The text is split at the last dot, so tool names may contain dots and argument names may not.
     *
     * @param target such as {@code fetchSupplierPage.url} or {@code mcp:web/fetch.url}
     * @return the argument
     * @throws IllegalArgumentException if either part is missing or the tool reference is malformed
     */
    public static ToolArgument parse(String target) {
        requireNonBlank(target, "rule target");
        int dot = target.lastIndexOf('.');
        if (dot <= 0 || dot == target.length() - 1) {
            throw new IllegalArgumentException("rule target must be 'tool.argument', was '" + target + "'");
        }
        return new ToolArgument(ToolId.parse(target.substring(0, dot)), target.substring(dot + 1));
    }

    /**
     * The ID of rules on this argument, as decisions and audit events name them.
     *
     * @return {@code fetchSupplierPage.url} for a local tool, {@code mcp:web/fetch.url} for a remote one
     */
    public String ruleId() {
        return (tool.isLocal() ? tool.name() : tool.toString()) + "." + name;
    }

    @Override
    public String toString() {
        return ruleId();
    }
}
