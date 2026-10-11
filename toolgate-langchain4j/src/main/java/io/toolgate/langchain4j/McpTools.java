package io.toolgate.langchain4j;

import dev.langchain4j.service.tool.ToolExecutor;
import io.toolgate.api.ToolId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Which MCP server a tool came from. LangChain4j's {@code McpToolExecutor} keeps its client, and the
 * server's own tool name, in private fields; the client's {@code key()} is the name the application
 * gave the connection. Read by reflection, so applications without {@code langchain4j-mcp} never
 * load MCP classes, and a release that renames the fields names the server {@code unattributed},
 * which no policy allows.
 */
final class McpTools {

    static final String UNATTRIBUTED_SERVER = "unattributed";
    private static final String MCP_EXECUTOR = "dev.langchain4j.mcp.McpToolExecutor";
    private static final String MCP_PROVIDER = "dev.langchain4j.mcp.McpToolProvider";

    private McpTools() {}

    static boolean isMcp(ToolExecutor executor) {
        return executor.getClass().getName().equals(MCP_EXECUTOR);
    }

    /** Whether a provider is LangChain4j's MCP provider, or one built on it. */
    static boolean isMcpProvider(Object provider) {
        for (Class<?> type = provider.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(MCP_PROVIDER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tool's identity: local tools by the name the model sees, MCP tools by connection and the
     * server's own tool name, which a name mapper may hide from the model. A tool from an MCP provider
     * whose executor is not LangChain4j's, as when the provider's tool wrapper wraps it, cannot be
     * attributed: it is named after the server {@code unattributed}, never as a local tool, which a
     * policy might allow.
     */
    static ToolId toolId(ToolExecutor executor, String modelName, boolean fromMcpProvider) {
        if (!isMcp(executor)) {
            return fromMcpProvider ? unattributed(modelName) : ToolId.local(modelName);
        }
        String name = fixedToolName(executor).orElse(modelName);
        try {
            return ToolId.mcp(connection(executor).orElse(UNATTRIBUTED_SERVER), name);
        } catch (IllegalArgumentException unusableName) {
            return unattributed(name);
        }
    }

    private static ToolId unattributed(String name) {
        return ToolId.mcp(UNATTRIBUTED_SERVER, name);
    }

    private static Optional<String> connection(ToolExecutor executor) {
        try {
            Object client = field(executor, "mcpClient");
            Method key = Class.forName("dev.langchain4j.mcp.client.McpClient", false, executor.getClass().getClassLoader())
                    .getMethod("key");
            return key.invoke(client) instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<String> fixedToolName(ToolExecutor executor) {
        try {
            return field(executor, "fixedToolName") instanceof Optional<?> fixed
                    ? fixed.filter(String.class::isInstance).map(String.class::cast)
                    : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
