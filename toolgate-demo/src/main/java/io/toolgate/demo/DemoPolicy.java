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
import static io.toolgate.api.policy.ArgumentRules.under;
import static io.toolgate.email.EmailRules.emailDomains;

import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The demo's policy, for the design's example cases and the trifecta measurement: Case A's invoice
 * job, Case B's support chat, Case C's ops assistant, Case E's ticket triage, and an interactive
 * inbox assistant. Each uses the design's defaults and nothing tuned for the trifecta measurement.
 */
@Configuration(proxyBeanMethods = false)
class DemoPolicy {

    /**
     * @param mode {@code enforce}, or {@code monitor} for the first weeks: decisions are logged, never applied
     */
    @Bean
    ToolGatePolicy toolGatePolicy(@Value("${demo.toolgate.mode:enforce}") String mode,
                                  @Value("${demo.ops.logs:/var/log/app}") String logs) {
        return ToolGatePolicy.builder()
                .mode(Mode.valueOf(mode.toUpperCase()))
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .tool("readTicket", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("searchKnowledgeBase", t -> t.effect(READ))
                .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("tagTicket", t -> t.effect(REVERSIBLE))
                .tool("replyToCustomer", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("draftReply", t -> t.effect(REVERSIBLE))
                .tool("listInbox", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("readMessage", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("searchContacts", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("readCalendar", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("createEvent", t -> t.effect(REVERSIBLE))
                .tool("sendMessage", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("getInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("readLog", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("fetch", t -> t.labels(EXTERNAL_SINK).effect(READ))
                .agent("invoice-processor", a -> a
                        // A nightly job: it acts as this service, in tenant Acme, never as a user.
                        .principal(Principal.service("invoice-processor", Tenant.of("acme")))
                        .allow("readInvoice", "fetchSupplierPage", "sendEmail", "draftEmail")
                        .downgrade("sendEmail", "draftEmail")
                        .rule("readInvoice.id", sameTenant("invoice"))
                        .rule("fetchSupplierPage.url", schemes("https"), hosts("*.suppliers.example"))
                        .rule("sendEmail.to", emailDomains("mycompany.com"))
                        .budget(b -> b.toolCalls(200)))
                .agent("ticket-triage", a -> a
                        // Case E: one session per ticket, nobody watching; replies become drafts once tainted.
                        .principal(Principal.service("ticket-triage", Tenant.of("acme")))
                        .allow("readTicket", "searchKnowledgeBase", "lookupCustomer", "tagTicket", "replyToCustomer",
                                "draftReply")
                        .downgrade("replyToCustomer", "draftReply")
                        .budget(b -> b.toolCalls(40)))
                .agent("support-chat", a -> a
                        // Case B: an in-app assistant acting for the signed-in user; five denials end the run.
                        .interactive(true)
                        .allow("getInvoice")
                        .rule("getInvoice.id", sameTenant("invoice")))
                .agent("ops-assistant", a -> a
                        // Case C: engineers' questions; logs carry customers' text.
                        .interactive(true)
                        .allow("readLog", "fetch")
                        .rule("readLog.path", under(logs))
                        .rule("fetch.url", schemes("https"), hosts("status.vendor.example")))
                .agent("inbox-assistant", a -> a
                        // Acts for the signed-in user, who confirms what the policy holds.
                        .interactive(true)
                        .allow("listInbox", "readMessage", "searchContacts", "readCalendar", "createEvent",
                                "sendMessage")
                        .budget(b -> b.toolCalls(40)))
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
