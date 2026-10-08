package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrincipalTest {

    /** Identity providers can repeat a role, for example once per group that grants it. */
    @Test
    void duplicateRolesCollapse() {
        Principal principal = Principal.user("maria", Tenant.of("acme"), "support", "support");

        assertThat(principal.roles()).containsExactly("support");
    }

    @Test
    void serviceHasNoRoles() {
        assertThat(Principal.service("invoice-processor", Tenant.none()).roles()).isEmpty();
    }
}
