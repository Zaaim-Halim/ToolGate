package io.toolgate.api.policy;

import io.toolgate.spi.ArgumentValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A rule on the value of one tool argument. Create them with {@link ArgumentRules}.
 *
 * <p>Sealed: each built-in kind has a validator tested against bypasses. Rules from other modules,
 * or a team's own, come through {@link Validated} and an {@link ArgumentValidator}.
 */
public sealed interface ArgumentRule
        permits ArgumentRule.Hosts, ArgumentRule.Schemes, ArgumentRule.Ports, ArgumentRule.Under, ArgumentRule.NotUnder,
                ArgumentRule.SameTenant, ArgumentRule.OwnedByPrincipal, ArgumentRule.AtMost, ArgumentRule.AtLeast,
                ArgumentRule.TotalAtMost, ArgumentRule.Validated {

    /**
     * An argument must pass a validator from outside core, such as {@code emailDomains}.
     *
     * @param validator the validator; its kind and fingerprint are read once, here
     * @param kind        the validator's kind
     * @param fingerprint the validator's configuration, for the policy hash
     */
    record Validated(ArgumentValidator validator, String kind, String fingerprint) implements ArgumentRule {

        private static final Pattern KIND = Pattern.compile("[A-Za-z][A-Za-z0-9.-]{0,63}");

        /**
         * Requires a well-formed kind and a fingerprint.
         *
         * @throws IllegalArgumentException if the kind is not a short name
         */
        public Validated {
            Objects.requireNonNull(validator, "validator");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (!KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException("a validator's kind must be a short name such as emailDomains, was '"
                        + kind + "'");
            }
        }

        /**
         * Reads the validator's kind and fingerprint.
         *
         * @param validator the validator
         */
        public Validated(ArgumentValidator validator) {
            this(validator, Objects.requireNonNull(validator, "validator").kind(), validator.fingerprint());
        }
    }

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

    /**
     * A numeric argument must be at most {@code limit}.
     *
     * @param limit       the largest allowed value
     * @param onViolation what happens to a call above it
     */
    record AtMost(BigDecimal limit, OnViolation onViolation) implements ArgumentRule {
        /** Requires both components. */
        public AtMost {
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(onViolation, "onViolation");
        }

        /**
         * The same limit with another action.
         *
         * @param action what happens to a call above the limit
         * @return the rule
         */
        public AtMost otherwise(OnViolation action) {
            return new AtMost(limit, action);
        }
    }

    /**
     * The sum of a numeric argument over the session's calls that ran must stay at most {@code limit}.
     * Negative values are denied, so a total can never be lowered.
     *
     * @param limit       the largest allowed total
     * @param onViolation what happens to a call that would pass it
     */
    record TotalAtMost(BigDecimal limit, OnViolation onViolation) implements ArgumentRule {
        /** Requires both components. */
        public TotalAtMost {
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(onViolation, "onViolation");
        }

        /**
         * The same limit with another action.
         *
         * @param action what happens to a call that would pass the limit
         * @return the rule
         */
        public TotalAtMost otherwise(OnViolation action) {
            return new TotalAtMost(limit, action);
        }
    }

    /**
     * A numeric argument must be at least {@code limit}.
     *
     * @param limit       the smallest allowed value
     * @param onViolation what happens to a call below it
     */
    record AtLeast(BigDecimal limit, OnViolation onViolation) implements ArgumentRule {
        /** Requires both components. */
        public AtLeast {
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(onViolation, "onViolation");
        }

        /**
         * The same limit with another action.
         *
         * @param action what happens to a call below the limit
         * @return the rule
         */
        public AtLeast otherwise(OnViolation action) {
            return new AtLeast(limit, action);
        }
    }
}
