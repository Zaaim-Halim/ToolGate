package io.toolgate.rules;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.validate.HostPattern;
import io.toolgate.validate.PathCheck;
import io.toolgate.validate.UrlCheck;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turns one argument's policy rules into the check that applies them. */
public final class ArgumentChecks {

    private static final int MAX_PORT = 65_535;

    /** The families of argument rules; one argument takes rules of one family only. */
    enum Family {
        /** Hosts, schemes and ports: the value is a URL. */
        URL,
        /** Under and notUnder: the value is a file path. */
        PATH,
        /** SameTenant and ownedByPrincipal: the value is a record ID, or a list of them. */
        OWNERSHIP,
        /** AtMost and atLeast: the value is a number. */
        NUMBER
    }

    private ArgumentChecks() {}

    /**
     * Validates one argument's rules, as the policy builder does.
     *
     * @param rules the argument's rules, each kind at most once
     * @throws IllegalArgumentException if they mix families, or a value is invalid
     */
    public static void validate(List<ArgumentRule> rules) {
        switch (family(rules)) {
            case URL -> urlRule(rules);
            case PATH -> pathRule(rules);
            case OWNERSHIP -> rules.forEach(ArgumentChecks::requireResourceType);
            case NUMBER -> NumericEvaluator.requireReachableLimits(rules);
        }
    }

    /**
     * Whether any of the rules needs an ownership resolver.
     *
     * @param rules an argument's rules
     * @return {@code true} for ownership rules
     */
    static boolean isOwnership(List<ArgumentRule> rules) {
        return family(rules) == Family.OWNERSHIP;
    }

    /**
     * Whether the rules are numeric limits.
     *
     * @param rules an argument's rules
     * @return {@code true} for atMost and atLeast
     */
    static boolean isNumeric(List<ArgumentRule> rules) {
        return family(rules) == Family.NUMBER;
    }

    /**
     * Compiles one argument's URL or path rules.
     *
     * @param rules     the argument's rules, each kind at most once
     * @param nameCheck why a URL host name must not be used, after any resolution; empty if fine
     * @return a function from the argument's value to the reason it is denied, or empty if allowed
     * @throws IllegalArgumentException if the rules mix families, or a value is invalid
     */
    static Function<String, Optional<String>> compile(List<ArgumentRule> rules,
                                                      Function<String, Optional<String>> nameCheck) {
        return switch (family(rules)) {
            case PATH -> {
                PathCheck.Rule rule = pathRule(rules);
                yield value -> switch (PathCheck.check(value, rule)) {
                    case PathCheck.Accepted accepted -> Optional.empty();
                    case PathCheck.Rejected(String reason) -> Optional.of(reason);
                };
            }
            case URL -> {
                UrlCheck.Rule rule = urlRule(rules);
                yield value -> switch (UrlCheck.check(value, rule)) {
                    case UrlCheck.Accepted(UrlCheck.Target target) ->
                            target.address().isPresent() ? Optional.empty() : nameCheck.apply(target.host());
                    case UrlCheck.Rejected(String reason) -> Optional.of(reason);
                };
            }
            case OWNERSHIP -> throw new IllegalArgumentException("ownership rules are checked by OwnershipEvaluator");
            case NUMBER -> throw new IllegalArgumentException("numeric rules are checked by NumericEvaluator");
        };
    }

    private static Family family(List<ArgumentRule> rules) {
        Set<Family> families = rules.stream().map(ArgumentChecks::family).collect(Collectors.toSet());
        if (families.size() != 1) {
            throw new IllegalArgumentException("an argument takes URL, path, ownership or numeric rules, not a mix");
        }
        return families.iterator().next();
    }

    private static Family family(ArgumentRule rule) {
        return switch (rule) {
            case ArgumentRule.Hosts hosts -> Family.URL;
            case ArgumentRule.Schemes schemes -> Family.URL;
            case ArgumentRule.Ports ports -> Family.URL;
            case ArgumentRule.Under under -> Family.PATH;
            case ArgumentRule.NotUnder notUnder -> Family.PATH;
            case ArgumentRule.SameTenant sameTenant -> Family.OWNERSHIP;
            case ArgumentRule.OwnedByPrincipal owned -> Family.OWNERSHIP;
            case ArgumentRule.AtMost atMost -> Family.NUMBER;
            case ArgumentRule.AtLeast atLeast -> Family.NUMBER;
        };
    }

    private static void requireResourceType(ArgumentRule rule) {
        switch (rule) {
            case ArgumentRule.SameTenant(String type) -> requireNonBlank(type, "resource type");
            case ArgumentRule.OwnedByPrincipal(String type) -> requireNonBlank(type, "resource type");
            default -> throw new IllegalStateException("not an ownership rule: " + rule);
        }
    }

    private static UrlCheck.Rule urlRule(List<ArgumentRule> rules) {
        Set<String> schemes = UrlCheck.Rule.DEFAULT.schemes();
        List<HostPattern> hosts = List.of();
        Set<Integer> ports = Set.of();
        for (ArgumentRule rule : rules) {
            switch (rule) {
                case ArgumentRule.Hosts(List<String> patterns) -> {
                    if (patterns.isEmpty()) {
                        throw new IllegalArgumentException("hosts needs at least one pattern");
                    }
                    hosts = patterns.stream().map(HostPattern::parse).toList();
                }
                case ArgumentRule.Schemes(Set<String> allowed) -> schemes = allowed;
                case ArgumentRule.Ports(Set<Integer> allowed) -> {
                    if (allowed.isEmpty() || allowed.stream().anyMatch(port -> port < 1 || port > MAX_PORT)) {
                        throw new IllegalArgumentException("ports must be 1 to " + MAX_PORT + ", was " + allowed);
                    }
                    ports = allowed;
                }
                default -> throw new IllegalStateException("not a URL rule: " + rule);
            }
        }
        return new UrlCheck.Rule(schemes, hosts, ports);
    }

    private static PathCheck.Rule pathRule(List<ArgumentRule> rules) {
        List<Path> under = new ArrayList<>();
        List<Path> notUnder = new ArrayList<>();
        for (ArgumentRule rule : rules) {
            switch (rule) {
                case ArgumentRule.Under(List<String> roots) -> under.addAll(roots(roots, "under"));
                case ArgumentRule.NotUnder(List<String> roots) -> notUnder.addAll(roots(roots, "notUnder"));
                default -> throw new IllegalStateException("not a path rule: " + rule);
            }
        }
        return new PathCheck.Rule(under, notUnder);
    }

    private static List<Path> roots(List<String> roots, String kind) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(kind + " needs at least one folder");
        }
        try {
            return roots.stream().map(Path::of).toList();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException(kind + ": " + invalid.getMessage(), invalid);
        }
    }
}
