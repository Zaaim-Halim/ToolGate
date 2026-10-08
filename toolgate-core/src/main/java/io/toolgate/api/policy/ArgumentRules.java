package io.toolgate.api.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Factories for {@link ArgumentRule}s, meant to be imported statically:
 *
 * <pre>{@code
 * .rule("fetchSupplierPage.url", hosts("*.suppliers.example"), schemes("https"))
 * }</pre>
 *
 * <p>Without {@link #schemes}, a URL may use {@code http} or {@code https}; without {@link #ports},
 * only the scheme's default port; without {@link #hosts}, any public host. Private, loopback,
 * link-local and cloud metadata addresses, and names such as {@code localhost}, are always denied.
 */
public final class ArgumentRules {

    private ArgumentRules() {}

    /**
     * Allows only these hosts.
     *
     * @param patterns exact names, or {@code *.example.com} for any subdomain of {@code example.com}
     *                 but not {@code example.com} itself; case is ignored
     * @return the rule
     */
    public static ArgumentRule hosts(String... patterns) {
        return new ArgumentRule.Hosts(List.of(patterns));
    }

    /**
     * Allows only these schemes.
     *
     * @param schemes {@code http}, {@code https} or both; case is ignored
     * @return the rule
     */
    public static ArgumentRule schemes(String... schemes) {
        return new ArgumentRule.Schemes(Arrays.stream(schemes)
                .map(scheme -> scheme.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * Allows these ports instead of only the scheme's default.
     *
     * @param ports port numbers from 1 to 65535
     * @return the rule
     */
    public static ArgumentRule ports(int... ports) {
        return new ArgumentRule.Ports(Arrays.stream(ports).boxed().collect(Collectors.toUnmodifiableSet()));
    }

    /** The kind of a rule, so a policy can give each kind at most once per argument. */
    static Class<? extends ArgumentRule> kind(ArgumentRule rule) {
        return switch (rule) {
            case ArgumentRule.Hosts hosts -> ArgumentRule.Hosts.class;
            case ArgumentRule.Schemes schemes -> ArgumentRule.Schemes.class;
            case ArgumentRule.Ports ports -> ArgumentRule.Ports.class;
        };
    }
}
