package io.toolgate.api;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.rules.ArgumentChecks;
import io.toolgate.validate.ResolvedAddresses;
import io.toolgate.validate.UrlCheck;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

/**
 * The checks behind URL argument rules, for code that connects where a model pointed after
 * {@code check} allowed it, such as SafeHttp: it re-checks each redirect with the same rules, and
 * each address a name resolves to just before connecting.
 */
public final class Destinations {

    private Destinations() {}

    /**
     * Why a URL must not be fetched under these rules, as a URL argument with the same rules would be
     * denied. Names are not resolved here; check the addresses with {@link #addressProblem}.
     *
     * @param url   the URL
     * @param rules {@code hosts}, {@code schemes} and {@code ports} rules; none means any public host over
     *              {@code http} or {@code https} on the scheme's own port
     * @return the reason, or empty if the URL may be fetched
     * @throws IllegalArgumentException if a rule is not a URL rule, or is invalid
     */
    public static Optional<String> urlProblem(String url, ArgumentRule... rules) {
        if (rules.length == 0) {
            return switch (UrlCheck.check(url, UrlCheck.Rule.DEFAULT)) {
                case UrlCheck.Accepted accepted -> Optional.empty();
                case UrlCheck.Rejected(String reason) -> Optional.of(reason);
            };
        }
        for (ArgumentRule rule : rules) {
            if (!(rule instanceof ArgumentRule.Hosts || rule instanceof ArgumentRule.Schemes || rule instanceof ArgumentRule.Ports)) {
                throw new IllegalArgumentException("only hosts, schemes and ports rules apply to a URL, not " + rule);
            }
        }
        return ArgumentChecks.compile(List.of(rules), host -> Optional.empty()).apply(url);
    }

    /**
     * Why an address must not be connected to: every IANA special-purpose range, such as loopback,
     * private networks, link-local cloud metadata and shared address space, and for IPv6 anything
     * outside global unicast.
     *
     * @param address a resolved address
     * @return the reason, or empty if the address is ordinary public unicast
     */
    public static Optional<String> addressProblem(InetAddress address) {
        return ResolvedAddresses.problem(address);
    }
}
