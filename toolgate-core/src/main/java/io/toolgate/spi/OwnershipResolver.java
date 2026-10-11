package io.toolgate.spi;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers who owns a record, for the {@code sameTenant} and {@code ownedByPrincipal} rules.
 *
 * <p>Written by the application, which knows where ownership is stored. Each method defaults to
 * "unknown", so an application implements only what its rules use. An unknown record is denied, so
 * a missing record and another tenant's record look the same to the model.
 *
 * <p>Called during {@code check}, on the request path: keep it fast, with a cache or with data the
 * tool loads anyway. A resolver that throws denies the call. Rules look records up in batches, one
 * call per rule and argument (or result path) however many IDs it names; the batch methods default to
 * one lookup per ID, and a resolver backed by a database can override them with one query.
 */
public interface OwnershipResolver {

    /**
     * The tenant that owns a record.
     *
     * @param resourceType the type the policy names, such as {@code invoice}
     * @param id           the record's ID, as the model gave it
     * @return the owning tenant's ID, or empty if the record is unknown
     */
    default Optional<String> tenantOf(String resourceType, String id) {
        return Optional.empty();
    }

    /**
     * The principal that owns a record.
     *
     * @param resourceType the type the policy names, such as {@code customer}
     * @param id           the record's ID, as the model gave it
     * @return the owning user's or service's ID, or empty if the record is unknown
     */
    default Optional<String> ownerOf(String resourceType, String id) {
        return Optional.empty();
    }

    /**
     * The tenants that own several records, in one lookup. Defaults to {@link #tenantOf} for each.
     *
     * @param resourceType the type the policy names, such as {@code invoice}
     * @param ids          the records' IDs, as the model gave them or the tool returned them
     * @return the owning tenant's ID for each record known; an ID left out is unknown, and denied
     */
    default Map<String, String> tenantsOf(String resourceType, Set<String> ids) {
        var tenants = new HashMap<String, String>();
        for (String id : ids) {
            tenantOf(resourceType, id).ifPresent(tenant -> tenants.put(id, tenant));
        }
        return tenants;
    }

    /**
     * The principals that own several records, in one lookup. Defaults to {@link #ownerOf} for each.
     *
     * @param resourceType the type the policy names, such as {@code customer}
     * @param ids          the records' IDs, as the model gave them or the tool returned them
     * @return the owning user's or service's ID for each record known; an ID left out is unknown, and denied
     */
    default Map<String, String> ownersOf(String resourceType, Set<String> ids) {
        var owners = new HashMap<String, String>();
        for (String id : ids) {
            ownerOf(resourceType, id).ifPresent(owner -> owners.put(id, owner));
        }
        return owners;
    }
}
