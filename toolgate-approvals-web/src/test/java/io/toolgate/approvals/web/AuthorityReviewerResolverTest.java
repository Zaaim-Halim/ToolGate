package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

/** The default reviewer: name and roles, and no tenant, so a multi-tenant application fails closed. */
class AuthorityReviewerResolverTest {

    @Test
    void takesTheNameAndRolesInNoTenant() {
        Principal reviewer = new AuthorityReviewerResolver()
                .reviewer(new TestingAuthenticationToken("lena", "secret", "ROLE_finance", "SCOPE_read", "FACTOR_PASSWORD"));

        assertThat(reviewer).isEqualTo(Principal.user("lena", Tenant.none(), "finance", "SCOPE_read"));
    }
}
