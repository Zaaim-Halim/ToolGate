package io.toolgate.rules;

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

/** Turns one argument's policy rules into the check that applies them to a string value. */
public final class ArgumentChecks {

    private static final int MAX_PORT = 65_535;

    private ArgumentChecks() {}

    /**
     * Validates one argument's rules, as the policy builder does.
     *
     * @param rules the argument's rules, each kind at most once
     * @throws IllegalArgumentException if they mix URL and path rules, or a value is invalid
     */
    public static void validate(List<ArgumentRule> rules) {
        compile(rules, host -> Optional.empty());
    }

    /**
     * Compiles one argument's rules.
     *
     * @param rules     the argument's rules, each kind at most once
     * @param nameCheck why a URL host name must not be used, after any resolution; empty if fine
     * @return a function from the argument's value to the reason it is denied, or empty if allowed
     * @throws IllegalArgumentException if the rules mix URL and path rules, or a value is invalid
     */
    static Function<String, Optional<String>> compile(List<ArgumentRule> rules,
                                                      Function<String, Optional<String>> nameCheck) {
        boolean pathRules = rules.stream().anyMatch(ArgumentChecks::isPathRule);
        boolean urlRules = rules.stream().anyMatch(rule -> !isPathRule(rule));
        if (pathRules && urlRules) {
            throw new IllegalArgumentException("an argument takes URL rules or path rules, not both");
        }
        if (pathRules) {
            PathCheck.Rule rule = pathRule(rules);
            return value -> switch (PathCheck.check(value, rule)) {
                case PathCheck.Accepted accepted -> Optional.empty();
                case PathCheck.Rejected(String reason) -> Optional.of(reason);
            };
        }
        UrlCheck.Rule rule = urlRule(rules);
        return value -> switch (UrlCheck.check(value, rule)) {
            case UrlCheck.Accepted(UrlCheck.Target target) ->
                    target.address().isPresent() ? Optional.empty() : nameCheck.apply(target.host());
            case UrlCheck.Rejected(String reason) -> Optional.of(reason);
        };
    }

    private static boolean isPathRule(ArgumentRule rule) {
        return switch (rule) {
            case ArgumentRule.Under under -> true;
            case ArgumentRule.NotUnder notUnder -> true;
            case ArgumentRule.Hosts hosts -> false;
            case ArgumentRule.Schemes schemes -> false;
            case ArgumentRule.Ports ports -> false;
        };
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
                case ArgumentRule.Under under -> throw new IllegalStateException("path rule in URL rules");
                case ArgumentRule.NotUnder notUnder -> throw new IllegalStateException("path rule in URL rules");
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
                case ArgumentRule.Hosts hosts -> throw new IllegalStateException("URL rule in path rules");
                case ArgumentRule.Schemes schemes -> throw new IllegalStateException("URL rule in path rules");
                case ArgumentRule.Ports ports -> throw new IllegalStateException("URL rule in path rules");
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
