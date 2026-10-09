package io.toolgate.demo;

import static io.toolgate.api.Effect.IRREVERSIBLE;
import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static io.toolgate.email.EmailRules.emailDomains;

import io.toolgate.api.Mode;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Case A's policy, and the ownership lookup its sameTenant rule needs. */
@Configuration(proxyBeanMethods = false)
class InvoicePolicy {

    /**
     * @param mode {@code enforce}, or {@code monitor} for the first weeks: decisions are logged, never applied
     */
    @Bean
    ToolGatePolicy toolGatePolicy(@Value("${demo.toolgate.mode:enforce}") String mode) {
        return ToolGatePolicy.builder()
                .mode(Mode.valueOf(mode.toUpperCase()))
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .agent("invoice-processor", a -> a
                        .allow("readInvoice", "fetchSupplierPage", "sendEmail", "draftEmail")
                        .downgrade("sendEmail", "draftEmail")
                        .rule("readInvoice.id", sameTenant("invoice"))
                        .rule("fetchSupplierPage.url", schemes("https"), hosts("*.suppliers.example"))
                        .rule("sendEmail.to", emailDomains("mycompany.com"))
                        .budget(b -> b.toolCalls(200)))
                .build();
    }

    @Bean
    OwnershipResolver invoiceOwners() {
        return new OwnershipResolver() {
            @Override
            public Optional<String> tenantOf(String resourceType, String id) {
                return "invoice".equals(resourceType) ? Optional.ofNullable(InvoiceTools.TENANT_OF_INVOICE.get(id)) : Optional.empty();
            }
        };
    }
}
