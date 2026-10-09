package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import org.junit.jupiter.api.Test;

/** An agent that runs as a job declares the service identity it acts as. */
class AgentPrincipalTest {

    private static final Principal JOB = new Principal(Principal.Kind.SERVICE, "invoice-processor", Tenant.of("acme"), java.util.Set.of("billing"));

    @Test
    void anAgentCanActAsAServiceIdentity() {
        ToolGatePolicy policy = ToolGatePolicy.builder().agent("invoice-processor", a -> a.principal(JOB)).build();

        assertThat(policy.agent("invoice-processor").orElseThrow().servicePrincipal()).hasValue(JOB);
    }

    @Test
    void onlyAServiceIdentityOnce() {
        assertThatThrownBy(() -> ToolGatePolicy.builder()
                .agent("a", a -> a.principal(Principal.user("maria", Tenant.of("acme")))).build())
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("service identity");
        assertThatThrownBy(() -> ToolGatePolicy.builder().agent("a", a -> a.principal(JOB).principal(JOB)).build())
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("twice");
    }

    @Test
    void theIdentityIsPartOfThePolicyHash() {
        String without = ToolGatePolicy.builder().agent("a", a -> a.allow("x")).build().hash();
        String with = ToolGatePolicy.builder().agent("a", a -> a.allow("x").principal(JOB)).build().hash();
        String otherTenant = ToolGatePolicy.builder()
                .agent("a", a -> a.allow("x").principal(new Principal(Principal.Kind.SERVICE, "invoice-processor", Tenant.of("globex"), java.util.Set.of("billing"))))
                .build().hash();

        assertThat(with).isNotEqualTo(without).isNotEqualTo(otherTenant);
    }
}
