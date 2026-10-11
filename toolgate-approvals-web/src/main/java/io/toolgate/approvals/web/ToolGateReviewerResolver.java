package io.toolgate.approvals.web;

import io.toolgate.api.Principal;
import org.springframework.security.core.Authentication;

/**
 * Turns the signed-in user into the principal ToolGate checks as a reviewer: their ID, roles and, above
 * all, tenant, since a reviewer's role counts only in their own tenant.
 *
 * <p>The default takes the user's name, and their authorities without the {@code ROLE_} prefix as roles,
 * in no tenant: in a multi-tenant application its reviewers then see only calls with no tenant, which
 * fails closed. Such an application declares a bean that reads the tenant from its own user model.
 */
@FunctionalInterface
public interface ToolGateReviewerResolver {

    /**
     * The reviewer for a signed-in user.
     *
     * @param authentication an authenticated, non-anonymous user
     * @return the principal, with the user's tenant and roles
     */
    Principal reviewer(Authentication authentication);
}
