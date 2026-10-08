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
 * <p>URL rules: without {@link #schemes}, a URL may use {@code http} or {@code https}; without
 * {@link #ports}, only the scheme's default port; without {@link #hosts}, any public host. Private,
 * loopback, link-local and cloud metadata addresses, and names such as {@code localhost}, are always
 * denied.
 *
 * <p>Path rules: {@link #under} and {@link #notUnder} compare real paths, after following symlinks.
 * A relative path is resolved against the single {@code under} root, so the tool must resolve it the
 * same way; with several roots it is denied. A new file is checked through its folder, which must
 * exist, and a file with several hard links is denied.
 *
 * <p>Ownership rules: {@link #sameTenant} and {@link #ownedByPrincipal} ask the application's
 * {@code OwnershipResolver} who owns the record an ID names. An unknown record is denied. The
 * argument may be a text or number ID, or a list of them, every one of which must pass.
 *
 * <p>One argument takes rules of one family only: URL, path or ownership.
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

    /**
     * Allows only paths inside these folders.
     *
     * @param roots absolute folders, without {@code .} or {@code ..}
     * @return the rule
     */
    public static ArgumentRule under(String... roots) {
        return new ArgumentRule.Under(List.of(roots));
    }

    /**
     * Denies paths inside these folders, even when an {@link #under} root contains them.
     *
     * @param roots absolute folders, without {@code .} or {@code ..}
     * @return the rule
     */
    public static ArgumentRule notUnder(String... roots) {
        return new ArgumentRule.NotUnder(List.of(roots));
    }

    /**
     * Allows only IDs of records that the principal's tenant owns. Also usable as a result check.
     *
     * @param resourceType the type of record, such as {@code invoice}, passed to the resolver
     * @return the rule
     */
    public static ArgumentRule.SameTenant sameTenant(String resourceType) {
        return new ArgumentRule.SameTenant(resourceType);
    }

    /**
     * Allows only IDs of records that the principal itself owns. Also usable as a result check.
     *
     * @param resourceType the type of record, such as {@code customer}, passed to the resolver
     * @return the rule
     */
    public static ArgumentRule.OwnedByPrincipal ownedByPrincipal(String resourceType) {
        return new ArgumentRule.OwnedByPrincipal(resourceType);
    }

    /** The kind of a rule, so a policy can give each kind at most once per argument. */
    static Class<? extends ArgumentRule> kind(ArgumentRule rule) {
        return switch (rule) {
            case ArgumentRule.Hosts hosts -> ArgumentRule.Hosts.class;
            case ArgumentRule.Schemes schemes -> ArgumentRule.Schemes.class;
            case ArgumentRule.Ports ports -> ArgumentRule.Ports.class;
            case ArgumentRule.Under under -> ArgumentRule.Under.class;
            case ArgumentRule.NotUnder notUnder -> ArgumentRule.NotUnder.class;
            case ArgumentRule.SameTenant sameTenant -> ArgumentRule.SameTenant.class;
            case ArgumentRule.OwnedByPrincipal owned -> ArgumentRule.OwnedByPrincipal.class;
        };
    }
}
