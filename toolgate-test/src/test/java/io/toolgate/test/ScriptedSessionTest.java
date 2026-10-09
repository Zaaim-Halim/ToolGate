package io.toolgate.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.Approval;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.Tenant;
import io.toolgate.spi.AuditEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/** Scripted sessions without the extension, and how they report failures and misuse. */
class ScriptedSessionTest {

    private static final Principal JOB = Principal.service("invoice-processor", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final ScriptedSession s = ScriptedSession.of(new InvoicePolicy().get(), "invoice-processor", JOB);

    @AfterEach
    void close() {
        s.close();
    }

    @Test
    void aFailedAssertionSaysWhatToolGateDecidedAndWhy() {
        s.tenantOf("invoice", "INV-900", "globex");

        assertThatThrownBy(() -> s.call("readInvoice", Map.of("id", "INV-900")).assertAllowed())
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("local/readInvoice {id=")
                .hasMessageContaining("expected allowed, but ToolGate decided Deny")
                .hasMessageContaining("readInvoice.id")
                .hasMessageContaining("globex");
    }

    @Test
    void assertDeniedByChecksTheRule() {
        assertThatThrownBy(() -> s.call("deleteEverything", Map.of()).assertDeniedBy("readInvoice.id"))
                .isInstanceOf(AssertionFailedError.class).hasMessageContaining("allow-list");
    }

    /** Taint builds up only because each call that runs has its result recorded, as in production. */
    @Test
    void callsThatRunRecordTheirResultSoTaintBuildsUp() {
        s.tenantOf("invoice", "INV-118", "acme");
        s.call("readInvoice", Map.of("id", "INV-118"));
        s.call("fetchSupplierPage", Map.of("url", "https://acme.suppliers.example/terms"));

        s.call("sendEmail", Map.of("to", "x@example.com")).assertDowngradedTo("draftEmail");
        s.newSession();
        s.call("sendEmail", Map.of("to", "x@example.com")).assertAllowed();
    }

    @Test
    void returningFeedsResultRules() {
        var search = s.call("searchInvoices", Map.of("q", "march"))
                .returning(Map.of("items", List.of(Map.of("tenantId", "acme"), Map.of("tenantId", "globex"))));

        search.assertAllowed().assertWithheld();
        assertThat(search.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOf(ResultDecision.Withhold.class));
        assertThatThrownBy(() -> s.call("searchInvoices", Map.of()).returning(Map.of("items", List.of())).assertWithheld())
                .isInstanceOf(AssertionFailedError.class).hasMessageContaining("released");
    }

    @Test
    void aResultOnlyBelongsToACallThatRanOnce() {
        assertThatIllegalStateException().isThrownBy(() -> s.call("deleteEverything", Map.of()).returning("x"))
                .withMessageContaining("only a call that runs");
        var read = s.call("searchInvoices", Map.of()).returning(Map.of("items", List.of()));
        assertThatIllegalStateException().isThrownBy(() -> read.returning("again")).withMessageContaining("already");
    }

    @Test
    void approvingADeferredCallReleasesIt() {
        var refund = s.call("issueRefund", Map.of("amount", 480)).assertDeferred();

        assertThat(s.approve(refund, LENA)).isInstanceOf(Approval.Released.class);
        assertThat(s.approve(refund, LENA)).as("released at most once").isNotInstanceOf(Approval.Released.class);
        assertThatIllegalStateException().isThrownBy(() -> s.approve(s.call("issueRefund", Map.of("amount", 5)), LENA));
    }

    @Test
    void theScriptedClockExpiresApprovals() {
        var refund = s.call("issueRefund", Map.of("amount", 480)).assertDeferred();
        s.advance(Duration.ofDays(30));

        assertThat(s.approve(refund, LENA)).isNotInstanceOf(Approval.Released.class);
    }

    @Test
    void callsCanSwitchPrincipal() {
        s.tenantOf("invoice", "INV-900", "globex");
        s.call("readInvoice", Map.of("id", "INV-900")).assertDenied();

        s.as(Principal.service("invoice-processor", Tenant.of("globex")));

        s.call("readInvoice", Map.of("id", "INV-900")).assertAllowed();
    }

    @Test
    void auditEventsAreReadLastAndEndTheScript() {
        s.call("deleteEverything", Map.of());

        assertThat(s.auditEvents()).filteredOn(AuditEvent.Decided.class::isInstance).hasSize(1);
        assertThat(s.auditEvents()).as("reading twice is fine").isNotEmpty();
        assertThatIllegalStateException().isThrownBy(() -> s.call("readInvoice", Map.of()))
                .withMessageContaining("read the audit events last");
    }

    @Test
    void plainJavaValuesBecomeArgumentValues() {
        assertThat(Args.value(0.1)).isEqualTo(ArgValue.number(new java.math.BigDecimal("0.1")));
        assertThat(Args.value(List.of(1, "a", true))).isEqualTo(ArgValue.list(List.of(ArgValue.number(1),
                ArgValue.text("a"), ArgValue.bool(true))));
        assertThat(Args.value(null)).isEqualTo(ArgValue.nullValue());
        assertThatIllegalArgumentException().isThrownBy(() -> Args.value(new Object())).withMessageContaining("java.lang.Object");
    }

    @Test
    void readsThePrincipalShorthand() {
        assertThat(Principals.parse("service:invoice-processor@acme")).isEqualTo(JOB);
        assertThat(Principals.parse("user:maria@acme#support,finance"))
                .isEqualTo(Principal.user("maria", Tenant.of("acme"), "support", "finance"));
        assertThat(Principals.parse("user:ops")).isEqualTo(Principal.user("ops", Tenant.none()));
        assertThatIllegalArgumentException().isThrownBy(() -> Principals.parse("robot:x"));
        assertThatIllegalArgumentException().isThrownBy(() -> Principals.parse("maria"));
    }
}
