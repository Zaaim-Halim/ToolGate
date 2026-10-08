package io.toolgate.api.policy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A rule on the value of one tool argument. Create them with {@link ArgumentRules}.
 *
 * <p>Sealed for now: every kind needs a validator that has been tested against bypasses. Custom
 * validators will come through an extension point.
 */
public sealed interface ArgumentRule
        permits ArgumentRule.Hosts, ArgumentRule.Schemes, ArgumentRule.Ports, ArgumentRule.Under, ArgumentRule.NotUnder,
                ArgumentRule.SameTenant, ArgumentRule.OwnedByPrincipal {

    /**
     * A URL argument must point at one of these hosts.
     *
     * @param patterns exact names, or {@code *.example.com} for any subdomain of {@code example.com}
     */
    record Hosts(List<String> patterns) implements ArgumentRule {
        /** Copies the patterns; they are validated when the policy is built. */
        public Hosts {
            patterns = List.copyOf(patterns);
        }
    }

    /**
     * A URL argument must use one of these schemes.
     *
     * @param schemes a subset of {@code http} and {@code https}
     */
    record Schemes(Set<String> schemes) implements ArgumentRule {
        /** Copies the schemes; they are validated when the policy is built. */
        public Schemes {
            schemes = Set.copyOf(schemes);
        }
    }

    /**
     * A URL argument must use one of these ports, instead of only its scheme's default port.
     *
     * @param ports port numbers
     */
    record Ports(Set<Integer> ports) implements ArgumentRule {
        /** Copies the ports; they are validated when the policy is built. */
        public Ports {
            ports = Set.copyOf(ports);
        }
    }

    /**
     * A path argument must lead, once symlinks are followed, inside one of these folders.
     *
     * @param roots absolute folders
     */
    record Under(List<String> roots) implements ArgumentRule {
        /** Copies the roots; they are validated when the policy is built. */
        public Under {
            roots = List.copyOf(roots);
        }
    }

    /**
     * A path argument must not lead, once symlinks are followed, inside any of these folders.
     *
     * @param roots absolute folders
     */
    record NotUnder(List<String> roots) implements ArgumentRule {
        /** Copies the roots; they are validated when the policy is built. */
        public NotUnder {
            roots = List.copyOf(roots);
        }
    }

    /**
     * An ID argument must name a record owned by the principal's tenant.
     *
     * @param resourceType the type of record, as the application's {@code OwnershipResolver} knows it
     */
    record SameTenant(String resourceType) implements ArgumentRule, ResultCheck {
        /** Requires the type. */
        public SameTenant {
            Objects.requireNonNull(resourceType, "resourceType");
        }
    }

    /**
     * An ID argument must name a record owned by the principal itself.
     *
     * @param resourceType the type of record, as the application's {@code OwnershipResolver} knows it
     */
    record OwnedByPrincipal(String resourceType) implements ArgumentRule, ResultCheck {
        /** Requires the type. */
        public OwnedByPrincipal {
            Objects.requireNonNull(resourceType, "resourceType");
        }
    }
}
