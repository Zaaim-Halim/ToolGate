package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.ownedByPrincipal;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Ownership rules through the public API, with an in-memory stand-in for the application's data. */
class OwnershipRulesTest {

    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");

    private static final Map<String, String> INVOICE_TENANTS = Map.of(
            "INV-118", "acme", "INV-119", "acme", "INV-900", "globex", "INV-901", "globex", "INV-902", "globex",
            "INV-903", "globex", "INV-904", "globex", "42", "acme");
    private static final Map<String, String> CUSTOMER_OWNERS = Map.of("C-1", "maria", "C-2", "omar");

    private final List<String> lookups = new ArrayList<>();
    private final AtomicInteger callCounter = new AtomicInteger();

    private final OwnershipResolver owners = new OwnershipResolver() {
        @Override
        public Optional<String> tenantOf(String resourceType, String id) {
            lookups.add(resourceType + ":" + id);
            return resourceType.equals("invoice") ? Optional.ofNullable(INVOICE_TENANTS.get(id)) : Optional.empty();
        }

        @Override
        public Optional<String> ownerOf(String resourceType, String id) {
            lookups.add(resourceType + ":" + id);
            return resourceType.equals("customer") ? Optional.ofNullable(CUSTOMER_OWNERS.get(id)) : Optional.empty();
        }
    };

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("support-chat", a -> a
                    .interactive(true)
                    .allow("getInvoice", "getCustomer", "mergeInvoices")
                    .rule("getInvoice.id", sameTenant("invoice"))
                    .rule("getCustomer.id", ownedByPrincipal("customer"))
                    .rule("mergeInvoices.ids", sameTenant("invoice")))
            .agent("back-office", a -> a.allow("getInvoice"))
            .build();

    /** Case B: Maria from Acme asks for Globex's invoices, then keeps trying. */
    @Test
    void caseBCrossTenantRequestsAreDeniedThenTheRunEnds() {
        ToolGate gate = gate(policy);

        assertThat(getInvoice(gate, "INV-118")).isEqualTo(new Decision.Allow("allow-list"));
        assertThat(getInvoice(gate, "INV-900")).isInstanceOfSatisfying(Decision.Deny.class, deny -> {
            assertThat(deny.ruleId()).isEqualTo("getInvoice.id");
            assertThat(deny.reason()).contains("belongs to tenant globex").contains("tenant acme");
        });
        for (String id : List.of("INV-901", "INV-902", "INV-903", "INV-904")) {
            assertThat(getInvoice(gate, id)).isInstanceOf(Decision.Deny.class);
        }

        assertThat(getInvoice(gate, "INV-119")).isInstanceOfSatisfying(Decision.Terminate.class,
                end -> assertThat(end.ruleId()).isEqualTo(BuiltInRules.DENIAL_BUDGET));
    }

    /** A missing record and another tenant's record must look the same, or IDs can be probed. */
    @Test
    void unknownRecordIsDeniedLikeAForeignOne() {
        ToolGate gate = gate(policy);

        Decision unknown = getInvoice(gate, "INV-404");
        Decision foreign = getInvoice(gate, "INV-900");

        assertThat(unknown).isInstanceOf(Decision.Deny.class);
        assertThat(foreign).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) unknown).reason()).contains("unknown");
    }

    @Test
    void principalWithoutTenantIsDenied() {
        ToolGate gate = gate(policy);

        assertThat(gate.check(call(Principal.user("guest", Tenant.none()), "getInvoice", ArgValue.text("INV-118"))))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> assertThat(deny.reason()).contains("no tenant"));
    }

    @Test
    void ownedByPrincipalComparesThePrincipalItself() {
        ToolGate gate = gate(policy);

        assertThat(gate.check(call(MARIA, "getCustomer", ArgValue.text("C-1")))).isInstanceOf(Decision.Allow.class);
        assertThat(gate.check(call(MARIA, "getCustomer", ArgValue.text("C-2"))))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> assertThat(deny.reason()).contains("not maria"));
    }

    @Test
    void everyIdInAListMustPass() {
        ToolGate gate = gate(policy);

        assertThat(gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-119")))).isInstanceOf(Decision.Allow.class);
        assertThat(gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-900")))).isInstanceOf(Decision.Deny.class);
        assertThat(gate.check(call(MARIA, "mergeInvoices", ArgValue.list(List.of())))).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void numberIdsAreLookedUpAsWritten() {
        ToolGate gate = gate(policy);

        assertThat(gate.check(call(MARIA, "getInvoice", ArgValue.number(42)))).isInstanceOf(Decision.Allow.class);
        assertThat(lookups).contains("invoice:42");
    }

    @Test
    void valuesThatAreNotIdsAreDenied() {
        ToolGate gate = gate(policy);
        List<ArgValue> notIds = List.of(ArgValue.nullValue(), ArgValue.bool(true), ArgValue.text(" "),
                ArgValue.object(Map.of("id", ArgValue.text("INV-118"))),
                ArgValue.list(List.of(ArgValue.list(List.of(ArgValue.text("INV-118"))))));

        for (ArgValue value : notIds) {
            assertThat(gate.check(call(MARIA, "getInvoice", value))).as("id = %s", value).isInstanceOf(Decision.Deny.class);
        }
    }

    @Test
    void resolverFailureDenies() {
        ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> { }).ownershipResolver(new OwnershipResolver() {
            @Override
            public Optional<String> tenantOf(String resourceType, String id) {
                throw new IllegalStateException("database down");
            }
        }).build();

        assertThat(getInvoice(gate, "INV-118")).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void rulesApplyOnlyToTheirAgent() {
        ToolGate gate = gate(policy);

        assertThat(gate.check(new ToolCall("c", MARIA, "back-office", "s2", ToolId.local("getInvoice"),
                ObjectValue.of(Map.of("id", ArgValue.text("INV-900"))), Instant.EPOCH)))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void buildingAGateWithoutTheResolverFails() {
        assertThatIllegalStateException().isThrownBy(() -> ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).build())
                .withMessageContaining("ownershipResolver");
        ToolGate.builder(ToolGatePolicy.builder().agent("a", a -> a.allow("x")).build()).build().close();
    }

    /** A resolver that answers lists in one query, as one backed by a database would. */
    @Test
    void aListOfIdsIsLookedUpInOneBatch() {
        var batches = new ArrayList<Set<String>>();
        OwnershipResolver batching = new OwnershipResolver() {
            @Override
            public Map<String, String> tenantsOf(String resourceType, Set<String> ids) {
                batches.add(Set.copyOf(ids));
                var known = new HashMap<String, String>();
                ids.stream().filter(INVOICE_TENANTS::containsKey).forEach(id -> known.put(id, INVOICE_TENANTS.get(id)));
                return known;
            }
        };
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> { })
                .ownershipResolver(batching).build()) {
            assertThat(gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-119", "INV-118"))))
                    .isInstanceOf(Decision.Allow.class);
            assertThat(gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-900", "INV-404"))))
                    .isInstanceOfSatisfying(Decision.Deny.class,
                            deny -> assertThat(deny.reason()).contains("INV-900").contains("globex"));
            assertThat(gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-404"))))
                    .as("an ID the batch leaves out is unknown").isInstanceOfSatisfying(Decision.Deny.class,
                            deny -> assertThat(deny.reason()).contains("'INV-404' is unknown"));
        }
        assertThat(batches).as("one lookup per call, duplicates removed").containsExactly(
                Set.of("INV-118", "INV-119"), Set.of("INV-118", "INV-900", "INV-404"), Set.of("INV-118", "INV-404"));
    }

    /** The default batch asks the single-record method once per distinct ID. */
    @Test
    void theDefaultBatchLooksUpEachDistinctIdOnce() {
        try (ToolGate gate = gate(policy)) {
            gate.check(call(MARIA, "mergeInvoices", ids("INV-118", "INV-119", "INV-118")));
        }
        assertThat(lookups).containsExactlyInAnyOrder("invoice:INV-118", "invoice:INV-119");
    }

    @Test
    void aBatchThatReturnsNullDenies() {
        OwnershipResolver broken = new OwnershipResolver() {
            @Override
            public Map<String, String> tenantsOf(String resourceType, Set<String> ids) {
                return null;
            }
        };
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> { })
                .ownershipResolver(broken).build()) {
            assertThat(getInvoice(gate, "INV-118")).isInstanceOf(Decision.Deny.class);
        }
    }

    @Test
    void ownershipRulesCombineButNotWithOtherFamilies() {
        ToolGatePolicy both = ToolGatePolicy.builder()
                .agent("a", a -> a.allow("getCustomer")
                        .rule("getCustomer.id", sameTenant("customer"), ownedByPrincipal("customer")))
                .build();
        assertThat(both.agent("a").orElseThrow().argumentRules()).hasSize(1);

        assertThatThrownBy(() -> ToolGatePolicy.builder()
                .agent("a", a -> a.allow("fetch").rule("fetch.id", sameTenant("invoice"), hosts("x.example"))).build())
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("not a mix");
        assertThatThrownBy(() -> ToolGatePolicy.builder()
                .agent("a", a -> a.allow("getInvoice").rule("getInvoice.id", sameTenant(" "))).build())
                .isInstanceOf(InvalidPolicyException.class);
    }

    private ToolGate gate(ToolGatePolicy gatePolicy) {
        return ToolGate.builder(gatePolicy).toolCatalog(Catalogs.declaring(gatePolicy)).auditSink(event -> { }).ownershipResolver(owners).build();
    }

    private Decision getInvoice(ToolGate gate, String id) {
        return gate.check(call(MARIA, "getInvoice", ArgValue.text(id)));
    }

    private static ArgValue ids(String... ids) {
        return ArgValue.list(Arrays.stream(ids).map(ArgValue::text).toList());
    }

    private ToolCall call(Principal principal, String tool, ArgValue id) {
        String argument = tool.equals("mergeInvoices") ? "ids" : "id";
        return new ToolCall("chat/" + callCounter.incrementAndGet(), principal, "support-chat", "maria-chat",
                ToolId.local(tool), ObjectValue.of(Map.of(argument, id)), Instant.EPOCH);
    }
}
