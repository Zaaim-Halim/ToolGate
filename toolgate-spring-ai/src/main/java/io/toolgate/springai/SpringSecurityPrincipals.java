package io.toolgate.springai;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in Spring Security user as a principal: the authentication's name, its authorities as
 * roles without Spring's {@code ROLE_} prefix, and no tenant. Anonymous users are nobody.
 */
final class SpringSecurityPrincipals implements ToolGatePrincipalResolver {

    private static final String ROLE_PREFIX = "ROLE_";
    /** Spring Security 7's authorities for how the user signed in, which are not roles. */
    private static final String FACTOR_PREFIX = "FACTOR_";

    @Override
    public Optional<Principal> resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String[] roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank() && !authority.startsWith(FACTOR_PREFIX))
                .map(authority -> authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority)
                .toArray(String[]::new);
        return Optional.of(Principal.user(authentication.getName(), Tenant.none(), roles));
    }
}
