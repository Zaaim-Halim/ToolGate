package io.toolgate.api;

import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static io.toolgate.api.policy.ResultRules.matchesPrincipal;
import static io.toolgate.api.policy.ResultRules.matchesTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.AuditJson;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Result rules end to end: a search tool that forgot to filter by tenant. */
class ResultRulesTest {

    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");

    private final AtomicInteger callCounter = new AtomicInteger();
    private final SessionStore store = new InMemorySessionStore();
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    private final OwnershipResolver owners = new OwnershipResolver() {
        @Override
        public Optional<String> tenantOf(String resourceType, String id) {
            if (id.equals("boom")) {
                throw new IllegalStateException("database down");
            }
            return Optional.ofNullable(Map.of("INV-118", "acme", "INV-900", "globex").get(id));
        }
    };

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .tool("searchInvoices", t -> t.labels(PRIVATE_DATA).effect(Effect.READ))
            .agent("support-chat", a -> a
                    .allow("searchInvoices", "findInvoiceIds", "myTasks", "getHelp")
                    .result("searchInvoices", "items[*].tenantId", matchesTenant())
                    .result("findInvoiceIds", "[*]", sameTenant("invoice"))
                    .result("myTasks", "tasks[*].assignee", matchesPrincipal()))
            .build();

    @Test
    void searchThatMixesTenantsIsWithheld() {
        try (ToolGate gate = gate(policy)) {
            ResultDecision decision = record(gate, "searchInvoices", invoices("acme", "globex"));

            assertThat(decision).isInstanceOfSatisfying(ResultDecision.Withhold.class, withhold -> {
                assertThat(withhold.ruleId()).isEqualTo("searchInvoices.result(items[*].tenantId)");
                assertThat(withhold.reason()).contains("'globex' is not tenant acme");
            });
        }
    }

    @Test
    void ownTenantsRecordsAndEmptySearchesAreReleased() {
        try (ToolGate gate = gate(policy)) {
            assertThat(record(gate, "searchInvoices", invoices("acme", "acme"))).isEqualTo(ResultDecision.RELEASE);
            assertThat(record(gate, "searchInvoices", invoices())).isEqualTo(ResultDecision.RELEASE);
        }
    }

    /** Output that cannot be checked cannot be trusted either. */
    @Test
    void outputThatCannotBeCheckedIsWithheld() {
        try (ToolGate gate = gate(policy)) {
            assertThat(record(gate, "searchInvoices", ArgValue.object(Map.of("results", ArgValue.list(List.of())))))
                    .as("renamed field").isInstanceOf(ResultDecision.Withhold.class);
            assertThat(record(gate, "searchInvoices", ArgValue.text("INV-900 (globex): 480 EUR")))
                    .as("plain text").isInstanceOf(ResultDecision.Withhold.class);
            assertThat(gate.recordResult(call("searchInvoices"), new ToolResult.Failed("ERROR: row 7 tenant globex")))
                    .as("error").isInstanceOfSatisfying(ResultDecision.Withhold.class,
                            withhold -> assertThat(withhold.reason()).contains("error cannot be checked"));
            assertThat(record(gate, "searchInvoices", ArgValue.object(Map.of("items", ArgValue.list(List.of(
                    ArgValue.object(Map.of("tenantId", ArgValue.nullValue()))))))))
                    .as("null value").isInstanceOf(ResultDecision.Withhold.class);
        }
    }

    @Test
    void idsAreCheckedThroughTheResolver() {
        try (ToolGate gate = gate(policy)) {
            assertThat(record(gate, "findInvoiceIds", ids("INV-118"))).isEqualTo(ResultDecision.RELEASE);
            assertThat(record(gate, "findInvoiceIds", ids("INV-118", "INV-900"))).isInstanceOf(ResultDecision.Withhold.class);
            assertThat(record(gate, "findInvoiceIds", ids("INV-404"))).isInstanceOf(ResultDecision.Withhold.class);
            assertThat(record(gate, "findInvoiceIds", ids("boom"))).isInstanceOfSatisfying(ResultDecision.Withhold.class,
                    withhold -> assertThat(withhold.reason()).contains("lookup failed"));
        }
    }

    @Test
    void matchesPrincipalComparesThePrincipalsId() {
        ArgValue mine = ArgValue.object(Map.of("tasks", ArgValue.list(List.of(
                ArgValue.object(Map.of("assignee", ArgValue.text("maria")))))));
        ArgValue someoneElses = ArgValue.object(Map.of("tasks", ArgValue.list(List.of(
                ArgValue.object(Map.of("assignee", ArgValue.text("omar")))))));
        try (ToolGate gate = gate(policy)) {
            assertThat(record(gate, "myTasks", mine)).isEqualTo(ResultDecision.RELEASE);
            assertThat(record(gate, "myTasks", someoneElses)).isInstanceOf(ResultDecision.Withhold.class);
        }
    }

    @Test
    void toolsWithoutResultRulesAreReleasedWhateverTheyReturn() {
        try (ToolGate gate = gate(policy)) {
            assertThat(gate.recordResult(call("getHelp"), new ToolResult.Failed("timeout"))).isEqualTo(ResultDecision.RELEASE);
        }
    }

    /** The model saw nothing, so nothing taints the session; but it was a denial, and probing ends the run. */
    @Test
    void withheldOutputAddsNoTaintAndSpendsTheDenialBudget() {
        try (ToolGate gate = gate(policy)) {
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(record(gate, "searchInvoices", invoices("globex"))).isInstanceOf(ResultDecision.Withhold.class);
            }

            assertThat(store.state("maria-chat").taint()).isEmpty();
            assertThat(gate.check(call("searchInvoices"))).isInstanceOfSatisfying(Decision.Terminate.class,
                    end -> assertThat(end.ruleId()).isEqualTo(BuiltInRules.DENIAL_BUDGET));
        }
    }

    @Test
    void releasedOutputTaintsAsBefore() {
        try (ToolGate gate = gate(policy)) {
            record(gate, "searchInvoices", invoices("acme"));
        }

        assertThat(store.state("maria-chat").taint()).containsKey(PRIVATE_DATA);
    }

    @Test
    void withholdingIsAudited() {
        try (ToolGate gate = gate(policy)) {
            record(gate, "searchInvoices", invoices("globex"));
        }

        assertThat(events).filteredOn(AuditEvent.ResultWithheld.class::isInstance).singleElement()
                .isInstanceOfSatisfying(AuditEvent.ResultWithheld.class, withheld -> {
                    assertThat(withheld.enforced()).isTrue();
                    assertThat(withheld.ruleId()).isEqualTo("searchInvoices.result(items[*].tenantId)");
                    assertThat(AuditJson.format(withheld)).contains("\"event\":\"toolgate.withheld\"")
                            .contains("\"enforced\":true");
                });
    }

    @Test
    void monitorModeReleasesButRecordsWhatItWouldHaveDone() {
        ToolGatePolicy monitored = ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("searchInvoices")
                        .result("searchInvoices", "items[*].tenantId", matchesTenant()))
                .build();
        try (ToolGate gate = gate(monitored)) {
            assertThat(record(gate, "searchInvoices", invoices("globex"))).isEqualTo(ResultDecision.RELEASE);
        }

        assertThat(store.state("maria-chat").denials()).isZero();
        assertThat(events).filteredOn(AuditEvent.ResultWithheld.class::isInstance).singleElement()
                .isInstanceOfSatisfying(AuditEvent.ResultWithheld.class, withheld -> assertThat(withheld.enforced()).isFalse());
    }

    private ToolGate gate(ToolGatePolicy gatePolicy) {
        return ToolGate.builder(gatePolicy).sessionStore(store).ownershipResolver(owners).auditSink(events::add).build();
    }

    private ResultDecision record(ToolGate gate, String tool, ArgValue output) {
        return gate.recordResult(call(tool), new ToolResult.Succeeded(output));
    }

    private ToolCall call(String tool) {
        return new ToolCall("chat/" + callCounter.incrementAndGet(), MARIA, "support-chat", "maria-chat",
                ToolId.local(tool), ObjectValue.empty(), Instant.EPOCH);
    }

    private static ArgValue invoices(String... tenants) {
        var items = new ArrayList<ArgValue>();
        for (String tenant : tenants) {
            items.add(ArgValue.object(Map.of("id", ArgValue.text("INV-" + items.size()), "tenantId", ArgValue.text(tenant))));
        }
        return ArgValue.object(Map.of("items", ArgValue.list(items)));
    }

    private static ArgValue ids(String... ids) {
        return ArgValue.list(Arrays.stream(ids).map(ArgValue::text).toList());
    }
}
