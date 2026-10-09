package io.toolgate.springai;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Field;
import java.util.Optional;
import org.springframework.ai.mcp.AsyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

/**
 * Which configured connection an MCP tool callback belongs to. Spring AI's MCP callbacks keep their
 * client private, so it is read by reflection: the only exact link between a callback and its server.
 * Names alone are not enough, since another server can offer a tool with the same name. The
 * connection's name is the client's title, which Spring Boot sets to the configured name; it is the
 * application's, never what the server says about itself.
 *
 * <p>Loaded only for MCP callbacks, so applications without MCP never touch these classes. If a
 * Spring AI release renames the field, every MCP tool is unattributed, and so denied.
 */
final class McpConnections {

    private static final Optional<Field> SYNC_CLIENT = field(SyncMcpToolCallback.class);
    private static final Optional<Field> ASYNC_CLIENT = field(AsyncMcpToolCallback.class);

    private McpConnections() {}

    /**
     * @return the connection's name, or empty if the callback's client or its title cannot be read
     */
    static Optional<String> connectionOf(ToolCallback callback) {
        Optional<McpSchema.Implementation> clientInfo = switch (callback) {
            case SyncMcpToolCallback sync -> read(SYNC_CLIENT, sync, McpSyncClient.class).map(McpSyncClient::getClientInfo);
            case AsyncMcpToolCallback async -> read(ASYNC_CLIENT, async, McpAsyncClient.class).map(McpAsyncClient::getClientInfo);
            default -> Optional.empty();
        };
        return clientInfo.map(McpSchema.Implementation::title).filter(title -> !title.isBlank());
    }

    private static <T> Optional<T> read(Optional<Field> field, Object callback, Class<T> type) {
        return field.map(client -> {
            try {
                return client.get(callback);
            } catch (IllegalAccessException unreadable) {
                return null;
            }
        }).filter(type::isInstance).map(type::cast);
    }

    private static Optional<Field> field(Class<?> callbackClass) {
        try {
            Field client = callbackClass.getDeclaredField("mcpClient");
            client.setAccessible(true);
            return Optional.of(client);
        } catch (NoSuchFieldException | RuntimeException renamedOrSealed) {
            return Optional.empty();
        }
    }
}
