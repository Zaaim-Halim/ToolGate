package io.toolgate.rules;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.spi.OwnershipResolver;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** One batch lookup per ownership rule, for the IDs an argument or a result path names. */
final class OwnershipLookup {

    private OwnershipLookup() {}

    /**
     * The owners the resolver knows, by ID: tenants for {@code sameTenant}, principals for
     * {@code ownedByPrincipal}. An ID missing from the map is unknown.
     */
    static Map<String, String> owners(OwnershipResolver resolver, ArgumentRule rule, Collection<String> ids) {
        var distinct = new LinkedHashSet<>(ids);
        Map<String, String> owners = switch (rule) {
            case ArgumentRule.SameTenant(String type) -> resolver.tenantsOf(type, distinct);
            case ArgumentRule.OwnedByPrincipal(String type) -> resolver.ownersOf(type, distinct);
            default -> throw new IllegalStateException("not an ownership rule: " + rule);
        };
        return Objects.requireNonNull(owners, "an ownership resolver returned null");
    }
}
