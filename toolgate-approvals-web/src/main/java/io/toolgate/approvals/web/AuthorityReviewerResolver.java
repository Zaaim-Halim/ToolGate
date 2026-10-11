package io.toolgate.approvals.web;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The default reviewer: the user's name and authorities, in no tenant. Spring Security 7's
 * {@code FACTOR_} authorities say how the user signed in, not what they may do, so they are not roles.
 */
final class AuthorityReviewerResolver implements ToolGateReviewerResolver {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String FACTOR_PREFIX = "FACTOR_";

    @Override
    public Principal reviewer(Authentication authentication) {
        String[] roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority -> !authority.isBlank() && !authority.startsWith(FACTOR_PREFIX))
                .map(authority -> authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority)
                .toArray(String[]::new);
        return Principal.user(authentication.getName(), Tenant.none(), roles);
    }
}
