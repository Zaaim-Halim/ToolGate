package io.toolgate.test;

import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static io.toolgate.api.policy.ResultRules.matchesTenant;

import io.toolgate.api.policy.ToolGatePolicy;
import java.util.function.Supplier;

/** The invoice processor of the design document, as a team would supply it to its tests. */
final class InvoicePolicy implements Supplier<ToolGatePolicy> {

    @Override
    public ToolGatePolicy get() {
        return ToolGatePolicy.builder()
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("searchInvoices", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .tool("issueRefund", t -> t.effect(REVERSIBLE))
                .agent("invoice-processor", a -> a
                        .reviewers("finance")
                        .allow("readInvoice", "searchInvoices", "fetchSupplierPage", "sendEmail", "draftEmail", "issueRefund")
                        .downgrade("sendEmail", "draftEmail")
                        .rule("readInvoice.id", sameTenant("invoice"))
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER))
                        .result("searchInvoices", "items[*].tenantId", matchesTenant()))
                .build();
    }
}
