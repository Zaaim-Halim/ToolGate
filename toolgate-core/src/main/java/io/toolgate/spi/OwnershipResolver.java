package io.toolgate.spi;

import java.util.Optional;

/**
 * Answers who owns a record, for the {@code sameTenant} and {@code ownedByPrincipal} rules.
 *
 * <p>Written by the application, which knows where ownership is stored. Each method defaults to
 * "unknown", so an application implements only what its rules use. An unknown record is denied, so
 * a missing record and another tenant's record look the same to the model.
 *
 * <p>Called during {@code check}, on the request path: keep it fast, with a cache or with data the
 * tool loads anyway. A resolver that throws denies the call.
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
}
