package io.toolgate.api;

import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.TaintAction.DEFER;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The taint rules, end to end: check, run, record, check again. */
class TaintRulesTest {

    private final AtomicInteger callCounter = new AtomicInteger();

    /** Case A: an invoice job is told to email bank details out. Rows 1, 2 and 4. */
    @Test
    void caseADowngradesTheSendOnceTheSessionIsTainted() {
        ToolGate gate = gate(invoicePolicy(agent -> { }));

        assertThat(run(gate, "readInvoice", Map.of("id", "INV-118"))).isEqualTo(new Decision.Allow("allow-list"));
        assertThat(run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms")))
                .as("a sink is fine while the session holds only private data")
                .isEqualTo(new Decision.Allow("allow-list"));

        Decision send = check(gate, "sendEmail", Map.of("to", "billing@mycompany.com", "body", "invoice and IBAN"));

        assertThat(send).isInstanceOfSatisfying(Decision.Downgrade.class, downgrade -> {
            assertThat(downgrade.safeTool()).isEqualTo(ToolId.local("draftEmail"));
            assertThat(downgrade.safeArgs().field("to")).contains(ArgValue.text("billing@mycompany.com"));
        });
    }

    /** Problem 4: the supplier example. The third call's host is allowed, yet it carries data out. */
    @Test
    void trifectaBlocksASinkWithoutADowngradeInAHeadlessAgent() {
        ToolGate gate = gate(invoicePolicy(agent -> { }));
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        Decision exfiltrate = check(gate, "fetchSupplierPage",
                Map.of("url", "https://acme.suppliers.example/track?ref=Maria+Lopez+DE89370400440532013000"));

        assertThat(exfiltrate).isInstanceOfSatisfying(Decision.Deny.class, deny -> {
            assertThat(deny.ruleId()).isEqualTo(BuiltInRules.TAINT_TRIFECTA);
            assertThat(deny.reason())
                    .contains("PRIVATE_DATA (from call job/1, local/readInvoice)")
                    .contains("UNTRUSTED_CONTENT (from call job/2, local/fetchSupplierPage)");
        });
    }

    @Test
    void trifectaDefersInAnInteractiveAgent() {
        ToolGate gate = gate(invoicePolicy(agent -> agent.interactive(true)));
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        assertThat(check(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/x")))
                .isInstanceOfSatisfying(Decision.Defer.class,
                        defer -> assertThat(defer.ruleId()).isEqualTo(BuiltInRules.TAINT_TRIFECTA));
    }

    /** Case E: a normal ticket's reply becomes a draft for the support agent to send in the morning. */
    @Test
    void caseEReplyBecomesADraftInTheTaintedTicketSession() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .tool("readTicket", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("replyToCustomer", t -> t.labels(EXTERNAL_SINK))
                .tool("draftReply", t -> t.effect(REVERSIBLE))
                .agent("agent", a -> a
                        .allow("readTicket", "lookupCustomer", "replyToCustomer", "draftReply")
                        .downgrade("replyToCustomer", "draftReply")
                        .budget(b -> b.toolCalls(40)))
                .build());

        assertThat(run(gate, "readTicket", Map.of("id", "48212"))).isInstanceOf(Decision.Allow.class);
        assertThat(run(gate, "lookupCustomer", Map.of("id", "C-5512"))).isInstanceOf(Decision.Allow.class);
        assertThat(check(gate, "replyToCustomer", Map.of("customer", "C-5512", "text", "Your refund is on its way")))
                .isInstanceOfSatisfying(Decision.Downgrade.class,
                        downgrade -> assertThat(downgrade.safeTool()).isEqualTo(ToolId.local("draftReply")));
    }

    /** A forgotten effect label fails safe: after untrusted content, the action waits for review. */
    @Test
    void undescribedToolIsHeldForReviewAfterUntrustedContent() {
        ToolGate gate = gate(invoicePolicy(agent -> agent.allow("archiveInvoice")));
        assertThat(check(gate, "archiveInvoice", Map.of("id", "INV-118"))).isInstanceOf(Decision.Allow.class);
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        assertThat(check(gate, "archiveInvoice", Map.of("id", "INV-118")))
                .isInstanceOfSatisfying(Decision.Defer.class,
                        defer -> assertThat(defer.ruleId()).isEqualTo(BuiltInRules.TAINT_IRREVERSIBLE));
    }

    /** The safe tool is checked too: a draft tool nobody marked reversible is itself held for review. */
    @Test
    void downgradeToAnUndescribedSafeToolIsHeldForReview() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .tool("fetch", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .agent("agent", a -> a.allow("fetch", "sendEmail", "draftEmail").downgrade("sendEmail", "draftEmail"))
                .build());
        run(gate, "fetch", Map.of("url", "https://x.example/"));

        assertThat(check(gate, "sendEmail", Map.of("to", "a@mycompany.com")))
                .isInstanceOfSatisfying(Decision.Defer.class, defer -> assertThat(defer.reason()).contains("draftEmail"));
    }

    @Test
    void agentsOwnRuleDefersOnlyWhenEveryLabelIsHeld() {
        ToolGate gate = gate(invoicePolicy(agent -> agent.allow("issueRefund")
                .whenTainted("issueRefund", PRIVATE_DATA, UNTRUSTED_CONTENT).then(DEFER)));
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        assertThat(check(gate, "issueRefund", Map.of("amount", "480"))).isInstanceOf(Decision.Allow.class);

        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        assertThat(check(gate, "issueRefund", Map.of("amount", "480")))
                .isInstanceOfSatisfying(Decision.Defer.class,
                        defer -> assertThat(defer.ruleId()).isEqualTo("issueRefund.whenTainted"));
    }

    /** Case A, row 3, in spirit: an argument rule denies outright even when a downgrade exists. */
    @Test
    void argumentDenialIsNeverSoftenedByTheDowngrade() {
        ToolGate gate = gate(invoicePolicy(agent -> agent.allow("postWebhook", "queueWebhook")
                .rule("postWebhook.url", hosts("hooks.mycompany.com"))
                .downgrade("postWebhook", "queueWebhook")));
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        assertThat(check(gate, "postWebhook", Map.of("url", "https://remit.acme-payments.example/")))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.ruleId()).isEqualTo("postWebhook.url"));
    }

    @Test
    void declassifyingLiftsTheRule() {
        ToolGate gate = gate(invoicePolicy(agent -> { }));
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        gate.declassify("job", UNTRUSTED_CONTENT, "terms parsed into SupplierTerms; free text dropped");

        assertThat(check(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/x")))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void monitoredTrifectaRecordsButAllows() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .ruleMode(BuiltInRules.TAINT_TRIFECTA, Mode.MONITOR)
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .agent("agent", a -> a.allow("readInvoice", "fetchSupplierPage"))
                .build());
        run(gate, "readInvoice", Map.of("id", "INV-118"));
        run(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        assertThat(check(gate, "fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/x")))
                .isEqualTo(new Decision.Allow(BuiltInRules.MODE_MONITOR));
    }

    /** The invoice-processor of Case A, with room for each test's additions. */
    private static ToolGatePolicy invoicePolicy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .tool("postWebhook", t -> t.labels(EXTERNAL_SINK))
                .tool("queueWebhook", t -> t.effect(REVERSIBLE))
                .tool("issueRefund", t -> t.effect(REVERSIBLE))
                .agent("agent", a -> {
                    a.allow("readInvoice", "fetchSupplierPage", "sendEmail", "draftEmail")
                            .downgrade("sendEmail", "draftEmail");
                    extra.accept(a);
                })
                .build();
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog().build();
    }

    /** Checks a call and, as an adapter would, records its result if it ran. */
    private Decision run(ToolGate gate, String tool, Map<String, String> args) {
        ToolCall call = call(tool, args);
        Decision decision = gate.check(call);
        switch (decision) {
            case Decision.Allow allowed -> gate.recordResult(call, new ToolResult.Succeeded(ArgValue.text("ok")));
            case Decision.Downgrade downgrade -> gate.recordResult(
                    call.withTool(downgrade.safeTool(), downgrade.safeArgs()), new ToolResult.Succeeded(ArgValue.text("ok")));
            case Decision.Deny denied -> { }
            case Decision.Defer deferred -> { }
            case Decision.Terminate terminated -> { }
        }
        return decision;
    }

    private Decision check(ToolGate gate, String tool, Map<String, String> args) {
        return gate.check(call(tool, args));
    }

    private ToolCall call(String tool, Map<String, String> args) {
        var values = new LinkedHashMap<String, ArgValue>();
        args.forEach((name, value) -> values.put(name, ArgValue.text(value)));
        return new ToolCall("job/" + callCounter.incrementAndGet(), Principal.service("invoice-processor", Tenant.of("acme")),
                "agent", "job", ToolId.local(tool), ObjectValue.of(values), Instant.EPOCH);
    }
}
