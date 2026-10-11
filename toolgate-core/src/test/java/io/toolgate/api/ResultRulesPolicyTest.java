package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.*;
import static io.toolgate.api.policy.ResultRules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ResultRule;
import io.toolgate.api.policy.ToolGatePolicy;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Result rules in the policy; both rule factories are imported with wildcards, so their names must not clash. */
class ResultRulesPolicyTest {

    @Test
    void keepsResultRulesWithReadableIds() {
        ToolGatePolicy policy = policy(a -> a.allow("searchInvoices", "findCustomer")
                .result("searchInvoices", "items[*].tenantId", matchesTenant())
                .result("findCustomer", "customer.id", ownedByPrincipal("customer")));

        assertThat(policy.agent("agent").orElseThrow().resultRules()).extracting(ResultRule::ruleId)
                .containsExactly("searchInvoices.result(items[*].tenantId)", "findCustomer.result(customer.id)");
    }

    @Test
    void rejectsInvalidResultRules() {
        assertInvalid(a -> a.allow("searchInvoices").result("searchInvoices", "items[0].tenantId", matchesTenant()));
        assertInvalid(a -> a.allow("searchInvoices").result("searchInvoices", "$.items", matchesTenant()));
        assertInvalid(a -> a.allow("other").result("searchInvoices", "items[*].tenantId", matchesTenant()));
        assertInvalid(a -> a.allow("searchInvoices").result("searchInvoices", "items[*].id", sameTenant(" ")));
        assertInvalid(a -> a.allow("searchInvoices").result("searchInvoices", "items[*].id", null));
    }

    @Test
    void resolverIsRequiredOnlyForIdChecks() {
        ToolGatePolicy direct = policy(a -> a.allow("searchInvoices")
                .result("searchInvoices", "items[*].tenantId", matchesTenant()));
        ToolGatePolicy byId = policy(a -> a.allow("searchInvoices")
                .result("searchInvoices", "items[*].id", sameTenant("invoice")));

        ToolGate.builder(direct).noDefaultLog().build().close();
        assertThatIllegalStateException().isThrownBy(() -> ToolGate.builder(byId).build())
                .withMessageContaining("ownershipResolver");
    }

    @Test
    void resultRulesChangeTheHash() {
        String none = policy(a -> a.allow("searchInvoices")).hash();
        String tenant = policy(a -> a.allow("searchInvoices")
                .result("searchInvoices", "items[*].tenantId", matchesTenant())).hash();
        String principal = policy(a -> a.allow("searchInvoices")
                .result("searchInvoices", "items[*].tenantId", matchesPrincipal())).hash();
        String otherPath = policy(a -> a.allow("searchInvoices")
                .result("searchInvoices", "items[*].tenant", matchesTenant())).hash();

        assertThat(List.of(none, tenant, principal, otherPath)).doesNotHaveDuplicates();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        return ToolGatePolicy.builder().agent("agent", agent).build();
    }

    private static void assertInvalid(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        assertThatThrownBy(() -> policy(agent)).isInstanceOf(InvalidPolicyException.class);
    }
}
