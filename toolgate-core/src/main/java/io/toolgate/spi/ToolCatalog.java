package io.toolgate.spi;

import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each tool looks like now, as the framework or MCP client last listed it. The gate reads it on
 * every call to a pinned tool, so it must be fast and thread-safe; keep it current whenever a server's
 * tool list changes.
 */
public interface ToolCatalog {

    /**
     * The tool's current definition.
     *
     * @param tool the tool
     * @return its definition, or empty if the tool is not known
     */
    Optional<ToolDefinition> definition(ToolId tool);

    /**
     * A catalog kept in memory, filled by the application or an adapter.
     *
     * @return a new, empty catalog
     */
    static Mutable inMemory() {
        return new Mutable();
    }

    /** A thread-safe in-memory catalog; a definition put again replaces the previous one. */
    final class Mutable implements ToolCatalog {

        private final Map<ToolId, ToolDefinition> definitions = new ConcurrentHashMap<>();

        private Mutable() {}

        /**
         * Adds or replaces a tool's definition.
         *
         * @param definition the definition
         * @return this catalog
         */
        public Mutable put(ToolDefinition definition) {
            definitions.put(Objects.requireNonNull(definition, "definition").tool(), definition);
            return this;
        }

        /**
         * Forgets a tool, such as one a server no longer lists.
         *
         * @param tool the tool
         * @return this catalog
         */
        public Mutable remove(ToolId tool) {
            definitions.remove(tool);
            return this;
        }

        @Override
        public Optional<ToolDefinition> definition(ToolId tool) {
            return Optional.ofNullable(definitions.get(tool));
        }
    }
}
