package io.toolgate.api;

import io.toolgate.api.policy.ToolArgument;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Tool catalogs for tests whose policies have argument rules. */
final class Catalogs {

    private Catalogs() {}

    /**
     * A catalog declaring, for every tool with argument rules, its ruled arguments and any extra names.
     *
     * @param policy the policy
     * @param extra  more argument names every such tool declares
     * @return the catalog
     */
    static ToolCatalog.Mutable declaring(ToolGatePolicy policy, String... extra) {
        var names = new LinkedHashMap<ToolId, Set<String>>();
        policy.agents().values().forEach(agent -> agent.argumentRules().keySet().forEach((ToolArgument argument) ->
                names.computeIfAbsent(argument.tool(), tool -> new TreeSet<>(Set.of(extra))).add(argument.name())));
        ToolCatalog.Mutable catalog = ToolCatalog.inMemory();
        names.forEach((tool, declared) -> catalog.put(definition(tool, declared)));
        return catalog;
    }

    /**
     * A definition whose schema declares exactly these arguments.
     *
     * @param tool      the tool
     * @param arguments the argument names
     * @return the definition
     */
    static ToolDefinition definition(ToolId tool, Set<String> arguments) {
        var properties = new LinkedHashMap<String, ArgValue>();
        arguments.forEach(name -> properties.put(name, ArgValue.object(Map.of())));
        return new ToolDefinition(tool, "", ArgValue.object(Map.of("type", ArgValue.text("object"),
                "properties", ArgValue.object(properties))));
    }
}
