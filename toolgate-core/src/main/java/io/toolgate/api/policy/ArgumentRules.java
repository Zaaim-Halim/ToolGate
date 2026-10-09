package io.toolgate.api.policy;

import io.toolgate.support.Decimals;
import java.math.BigDecimal;
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
 * <p>Numeric rules: {@link #atMost} and {@link #atLeast} limit each call, and {@link #totalAtMost}
 * the sum over the session's calls that ran (allowed calls, and deferred ones once released), so a
 * model cannot split one large amount into several small ones. Each denies a call past the limit, or with
 * {@code .otherwise(DEFER)} hold it for review. One deny limit and one defer limit of each kind may
 * share an argument, such as deny above 1000 and review above 200. The value may be a number or text
 * that is a plain decimal; anything a tool might read differently, such as {@code 1e3}, is denied.
 *
 * <p>One argument takes rules of one family only: URL, path, ownership or numeric.
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

    /**
     * Allows values up to a limit; above it, the call is denied.
     *
     * @param limit a plain decimal, such as {@code "200"} or {@code "199.99"}; never a double, so no
     *              rounding can move the limit
     * @return the rule; {@code .otherwise(DEFER)} reviews instead of denying
     * @throws IllegalArgumentException if the limit is not a plain decimal
     */
    public static ArgumentRule.AtMost atMost(String limit) {
        return new ArgumentRule.AtMost(decimal(limit), OnViolation.DENY);
    }

    /**
     * Allows values down to a limit; below it, the call is denied.
     *
     * @param limit a plain decimal, such as {@code "1"} or {@code "-50"}
     * @return the rule; {@code .otherwise(DEFER)} reviews instead of denying
     * @throws IllegalArgumentException if the limit is not a plain decimal
     */
    public static ArgumentRule.AtLeast atLeast(String limit) {
        return new ArgumentRule.AtLeast(decimal(limit), OnViolation.DENY);
    }

    private static BigDecimal decimal(String limit) {
        return Decimals.parsePlain(limit).orElseThrow(() -> new IllegalArgumentException(
                "limit must be a plain decimal such as \"200\" or \"199.99\", was " + limit));
    }

    /**
     * Allows the session's total of this argument, over calls that ran, up to a limit; a call that
     * would pass it is denied. Negative values are denied, so a total can never be lowered.
     *
     * @param limit a plain decimal, such as {@code "500"}
     * @return the rule; {@code .otherwise(DEFER)} reviews instead of denying
     * @throws IllegalArgumentException if the limit is not a plain decimal
     */
    public static ArgumentRule.TotalAtMost totalAtMost(String limit) {
        return new ArgumentRule.TotalAtMost(decimal(limit), OnViolation.DENY);
    }

    /**
     * The kind of a rule, so a policy can give each kind at most once per argument. A limit's kind
     * includes its action, so one deny and one defer limit may share an argument.
     */
    static String kind(ArgumentRule rule) {
        return switch (rule) {
            case ArgumentRule.Hosts hosts -> "hosts";
            case ArgumentRule.Schemes schemes -> "schemes";
            case ArgumentRule.Ports ports -> "ports";
            case ArgumentRule.Under under -> "under";
            case ArgumentRule.NotUnder notUnder -> "notUnder";
            case ArgumentRule.SameTenant sameTenant -> "sameTenant";
            case ArgumentRule.OwnedByPrincipal owned -> "ownedByPrincipal";
            case ArgumentRule.AtMost(var limit, OnViolation action) ->
                    action == OnViolation.DENY ? "atMost" : "atMost otherwise " + action;
            case ArgumentRule.AtLeast(var limit, OnViolation action) ->
                    action == OnViolation.DENY ? "atLeast" : "atLeast otherwise " + action;
            case ArgumentRule.TotalAtMost(var limit, OnViolation action) ->
                    action == OnViolation.DENY ? "totalAtMost" : "totalAtMost otherwise " + action;
        };
    }
}
