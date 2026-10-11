package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RoleRulesTest {

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("support-chat", a -> a
                    .allow("getInvoice", "issueRefund")
                    .requireRole("issueRefund", "finance", "support-lead"))
            .build();
    private final ToolGate gate = ToolGate.builder(policy).noDefaultLog().build();

    @Test
    void anyOneOfTheRolesIsEnough() {
        assertThat(check(Principal.user("lena", Tenant.of("acme"), "finance"), "issueRefund"))
                .isInstanceOf(Decision.Allow.class);
        assertThat(check(Principal.user("omar", Tenant.of("acme"), "support", "support-lead"), "issueRefund"))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void principalWithoutTheRoleIsDenied() {
        assertThat(check(Principal.user("maria", Tenant.of("acme"), "support"), "issueRefund"))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo("issueRefund.roles");
                    assertThat(deny.reason()).contains("[finance, support-lead]");
                });
        assertThat(check(Principal.service("refund-job", Tenant.of("acme")), "issueRefund"))
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void otherToolsNeedNoRole() {
        assertThat(check(Principal.user("maria", Tenant.of("acme")), "getInvoice")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void rejectsInvalidRoleRules() {
        assertInvalid(a -> a.allow("issueRefund").requireRole("issueRefund"));
        assertInvalid(a -> a.allow("issueRefund").requireRole("issueRefund", " "));
        assertInvalid(a -> a.allow("getInvoice").requireRole("issueRefund", "finance"));
        assertInvalid(a -> a.allow("issueRefund").requireRole("issueRefund", "a").requireRole("issueRefund", "b"));
    }

    @Test
    void rolesChangeThePolicyHash() {
        ToolGatePolicy other = ToolGatePolicy.builder()
                .agent("support-chat", a -> a.allow("getInvoice", "issueRefund").requireRole("issueRefund", "finance"))
                .build();

        assertThat(other.hash()).isNotEqualTo(policy.hash());
    }

    private Decision check(Principal principal, String tool) {
        return gate.check(new ToolCall("c-" + principal.id() + tool, principal, "support-chat", "s-" + principal.id(),
                ToolId.local(tool), ObjectValue.empty(), Instant.EPOCH));
    }

    private static void assertInvalid(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        assertThatThrownBy(() -> ToolGatePolicy.builder().agent("a", agent).build())
                .isInstanceOf(InvalidPolicyException.class);
    }
}
