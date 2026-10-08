package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;

/**
 * A check on what a tool returned, before the model reads it: every value {@code path} reaches in
 * the output must pass {@code check}, or the whole output is withheld.
 *
 * @param tool  the tool
 * @param path  where the values are, such as {@code items[*].tenantId}
 * @param check what each value must satisfy
 */
public record ResultRule(ToolId tool, String path, ResultCheck check) {

    /** Requires every component; the path is validated when the policy is built. */
    public ResultRule {
        Objects.requireNonNull(tool, "tool");
        requireNonBlank(path, "path");
        Objects.requireNonNull(check, "check");
    }

    /**
     * The rule's ID in decisions and audit events.
     *
     * @return such as {@code searchInvoices.result(items[*].tenantId)}
     */
    public String ruleId() {
        return (tool.isLocal() ? tool.name() : tool.toString()) + ".result(" + path + ")";
    }
}
