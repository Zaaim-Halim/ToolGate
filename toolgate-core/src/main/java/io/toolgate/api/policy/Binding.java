package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;

/**
 * An argument bound to a trusted field of an earlier result: the argument must equal a value that
 * field held in an output the session received before it read untrusted content, or the one output
 * that brought untrusted content in. A bound call goes where the application's own data says, so
 * taint does not hold it; see {@link ToolGatePolicy.AgentBuilder#bind}.
 *
 * @param argument the bound argument
 * @param source   the tool whose output holds the trusted field
 * @param field    the path to the field, declared trusted on the source tool
 */
public record Binding(ToolArgument argument, ToolId source, String field) {

    /** Requires every part. */
    public Binding {
        Objects.requireNonNull(argument, "argument");
        Objects.requireNonNull(source, "source");
        requireNonBlank(field, "field");
    }

    /**
     * The binding's rule ID, as decisions and audit events name it.
     *
     * @return such as {@code replyToCustomer.customerId.bound}
     */
    public String ruleId() {
        return argument.ruleId() + ".bound";
    }

    /**
     * The key under which a session keeps the field's trusted values.
     *
     * @return such as {@code local/readTicket#customer}
     */
    public String sourceKey() {
        return sourceKey(source, field);
    }

    /**
     * The key under which a session keeps a tool's trusted field values.
     *
     * @param tool  the tool
     * @param field the path to the field
     * @return the key
     */
    public static String sourceKey(ToolId tool, String field) {
        return tool + "#" + field;
    }
}
