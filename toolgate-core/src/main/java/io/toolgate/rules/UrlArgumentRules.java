package io.toolgate.rules;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.validate.HostPattern;
import io.toolgate.validate.UrlCheck;
import java.util.List;
import java.util.Set;

/** Turns a policy's URL argument rules into the settings {@link UrlCheck} applies. */
public final class UrlArgumentRules {

    private static final int MAX_PORT = 65_535;

    private UrlArgumentRules() {}

    /**
     * Compiles the rules for one argument.
     *
     * @param rules the argument's rules, each kind at most once
     * @return the check settings
     * @throws IllegalArgumentException if a pattern, scheme or port is invalid
     */
    public static UrlCheck.Rule compile(List<ArgumentRule> rules) {
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
            }
        }
        return new UrlCheck.Rule(schemes, hosts, ports);
    }
}
