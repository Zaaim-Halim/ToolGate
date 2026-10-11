package io.toolgate.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A LangChain4j tool specification as ToolGate's definition: its description and its parameters as
 * a JSON schema, which pins and declared arguments are checked against.
 */
final class ToolSpecs {

    private static final String NO_PARAMETERS = "{\"type\":\"object\",\"properties\":{}}";

    private ToolSpecs() {}

    /** The definition, or empty if the schema cannot be read or canonicalized: the call is then refused. */
    static Optional<ToolDefinition> definition(ToolId tool, ToolSpecification specification) {
        try {
            String description = specification.description() == null ? "" : specification.description();
            // JsonSchemaElementUtils is internal to LangChain4j; if a release changes it, calls fail closed.
            String schema = specification.parameters() == null
                    ? NO_PARAMETERS
                    : ArgValueJson.write(value(JsonSchemaElementUtils.toMap(specification.parameters())));
            return Optional.of(ToolDefinition.of(tool, description, schema));
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }

    private static ArgValue value(Object object) {
        return switch (object) {
            case null -> ArgValue.nullValue();
            case String text -> ArgValue.text(text);
            case Boolean flag -> ArgValue.bool(flag);
            case BigDecimal decimal -> ArgValue.number(decimal);
            case Number number -> ArgValue.number(new BigDecimal(number.toString()));
            case Map<?, ?> map -> {
                var fields = new LinkedHashMap<String, ArgValue>();
                map.forEach((key, field) -> fields.put(String.valueOf(key), value(field)));
                yield ArgValue.object(fields);
            }
            case List<?> list -> ArgValue.list(list.stream().map(ToolSpecs::value).toList());
            case Object[] array -> ArgValue.list(java.util.Arrays.stream(array).map(ToolSpecs::value).toList());
            default -> ArgValue.text(object.toString());
        };
    }
}
