package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

/**
 * The tenant a principal is scoped to, if any.
 *
 * <p>Modeled as a closed type rather than a nullable string, so tenant rules must handle the
 * unscoped case explicitly instead of forgetting a null check.
 */
public sealed interface Tenant permits Tenant.Scoped, Tenant.Unscoped {

    /** The value for principals that belong to no tenant. */
    Tenant NONE = new Unscoped();

    /** A principal scoped to the tenant with this ID. */
    static Tenant of(String id) {
        return new Scoped(id);
    }

    /** A principal that belongs to no tenant. */
    static Tenant none() {
        return NONE;
    }

    /**
     * A principal scoped to one tenant.
     *
     * @param id the tenant's ID, as the application's ownership lookup reports it
     */
    record Scoped(String id) implements Tenant {
        /** Rejects a blank ID, which would match other blank IDs by accident. */
        public Scoped {
            requireNonBlank(id, "tenant id");
        }
    }

    /** A principal that belongs to no tenant; tenant rules deny it any tenant-owned record. */
    record Unscoped() implements Tenant {}
}
