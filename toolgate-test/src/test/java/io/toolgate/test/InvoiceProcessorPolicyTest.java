package io.toolgate.test;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The example of the design document, run through the extension as a team would write it. */
@ToolGateTest(policy = InvoicePolicy.class, agent = "invoice-processor", principal = "service:invoice-processor@acme")
class InvoiceProcessorPolicyTest {

    @Test
    void injectedSupplierPageCannotEmailInvoiceData(ScriptedSession s) {
        s.tenantOf("invoice", "INV-118", "acme");
        s.call("readInvoice", Map.of("id", "INV-118")).assertAllowed();
        s.call("fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms")).assertAllowed();
        s.call("sendEmail", Map.of("to", "billing@mycompany.com")).assertDowngradedTo("draftEmail");
    }

    @Test
    void cannotReadAnotherTenantsInvoice(ScriptedSession s) {
        s.tenantOf("invoice", "INV-900", "globex");
        s.call("readInvoice", Map.of("id", "INV-900")).assertDenied().assertDeniedBy("readInvoice.id");
    }

    @Test
    void eachTestGetsAFreshSession(ScriptedSession s) {
        s.call("sendEmail", Map.of("to", "billing@mycompany.com")).assertAllowed();
    }
}
