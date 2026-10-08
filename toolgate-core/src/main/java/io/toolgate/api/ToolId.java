package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Optional;

/**
 * A tool's identity, including where it came from.
 *
 * <p>The source is part of the identity so that a tool registered by an MCP server can never pass
 * for a local tool with the same name: {@code local/sendEmail} and {@code mcp:calendar/sendEmail}
 * are different tools.
 *
 * @param source {@value #LOCAL} or {@code mcp:<server>}
 * @param name   the tool's name within its source
 */
public record ToolId(String source, String name) {

    /** The source of tools defined in the application itself. */
    public static final String LOCAL = "local";

    private static final String MCP_PREFIX = "mcp:";
    private static final char SEPARATOR = '/';

    /** Rejects unknown sources and any name that would make the printed form ambiguous. */
    public ToolId {
        requireNonBlank(source, "tool source");
        requireNonBlank(name, "tool name");
        if (!source.equals(LOCAL) && !isMcpSource(source)) {
            throw new IllegalArgumentException(
                    "tool source must be '" + LOCAL + "' or 'mcp:<server>', was '" + source + "'");
        }
        if (name.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("tool name must not contain '/', was '" + name + "'");
        }
    }

    /**
     * A tool defined in the application.
     *
     * @param name the tool's name
     * @return the tool's identity
     */
    public static ToolId local(String name) {
        return new ToolId(LOCAL, name);
    }

    /**
     * A tool registered by an MCP server.
     *
     * @param server the server's name, as the policy lists it
     * @param name   the tool's name on that server
     * @return the tool's identity
     */
    public static ToolId mcp(String server, String name) {
        return new ToolId(MCP_PREFIX + server, name);
    }

    /**
     * Parses a tool reference as written in a policy.
     *
     * <p>A bare name such as {@code sendEmail} means the local tool, because that is what an
     * application author means by it; a remote tool must always be named with its server, as in
     * {@code mcp:jira/search_issues}. {@code local/sendEmail} is also accepted.
     *
     * @param reference the reference as written in a policy
     * @return the tool's identity
     * @throws IllegalArgumentException if the reference is malformed
     */
    public static ToolId parse(String reference) {
        requireNonBlank(reference, "tool reference");
        int separator = reference.indexOf(SEPARATOR);
        if (separator < 0) {
            if (reference.startsWith(MCP_PREFIX)) {
                throw new IllegalArgumentException(
                        "MCP tool reference needs a tool name, as in 'mcp:server/tool', was '" + reference + "'");
            }
            return local(reference);
        }
        return new ToolId(reference.substring(0, separator), reference.substring(separator + 1));
    }

    /**
     * Whether the application itself defines this tool.
     *
     * @return {@code true} for a local tool
     */
    public boolean isLocal() {
        return source.equals(LOCAL);
    }

    /**
     * The MCP server that registered this tool.
     *
     * @return the server's name, or empty for a local tool
     */
    public Optional<String> mcpServer() {
        return isLocal() ? Optional.empty() : Optional.of(source.substring(MCP_PREFIX.length()));
    }

    /** The form used in policies and audit events, such as {@code mcp:jira/search_issues}. */
    @Override
    public String toString() {
        return source + SEPARATOR + name;
    }

    private static boolean isMcpSource(String source) {
        if (!source.startsWith(MCP_PREFIX)) {
            return false;
        }
        String server = source.substring(MCP_PREFIX.length());
        return !server.isBlank() && server.indexOf(SEPARATOR) < 0;
    }
}
