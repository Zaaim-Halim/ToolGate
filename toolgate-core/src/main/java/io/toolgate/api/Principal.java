package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Who an agent acts for: the signed-in user of a request, or a service identity for a job.
 *
 * <p>An agent can never do more than its principal, so every {@link ToolCall} carries one.
 *
 * @param kind   whether this is a user or a service identity
 * @param id     the user's or service's ID
 * @param tenant the tenant the principal is scoped to, or {@link Tenant#NONE}
 * @param roles  the principal's roles, for role rules
 */
public record Principal(Kind kind, String id, Tenant tenant, Set<String> roles) {

    /** The two kinds of principal. */
    public enum Kind {
        /** A person, resolved from the request. */
        USER,
        /** A service identity, configured for a scheduled job or event consumer. */
        SERVICE
    }

    /** Validates every component and copies the roles. */
    public Principal {
        Objects.requireNonNull(kind, "kind");
        requireNonBlank(id, "principal id");
        Objects.requireNonNull(tenant, "tenant");
        roles = Set.copyOf(roles);
    }

    /**
     * A user principal. Repeated roles collapse into one.
     *
     * @param id     the user's ID
     * @param tenant the user's tenant, or {@link Tenant#NONE}
     * @param roles  the user's roles
     * @return the principal
     */
    public static Principal user(String id, Tenant tenant, String... roles) {
        return new Principal(Kind.USER, id, tenant, Set.copyOf(Arrays.asList(roles)));
    }

    /**
     * A service principal with no roles.
     *
     * @param id     the service identity, for example the job's name
     * @param tenant the tenant the job works for, or {@link Tenant#NONE}
     * @return the principal
     */
    public static Principal service(String id, Tenant tenant) {
        return new Principal(Kind.SERVICE, id, tenant, Set.of());
    }
}
