package io.toolgate.validate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a URL argument may be used, without resolving any name.
 *
 * <p>The URL is parsed strictly, and anything an HTTP client might read differently is rejected
 * rather than interpreted: characters outside printable ASCII (which also rejects internationalized
 * names; a policy lists their {@code xn--} form), backslashes, user info, percent-encoded or
 * unparseable hosts, and IP literals in legacy forms. What remains is checked against the scheme,
 * host and port rules, against names that are internal by definition, and, for IP literals,
 * against {@link SpecialAddresses}.
 *
 * <p>Names are not resolved, so a public name that resolves to a private address passes here; the
 * address the client actually connects to is checked by SafeHttp, or by an optional resolver.
 */
public final class UrlCheck {

    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443);
    private static final char FIRST_PRINTABLE = '!';
    private static final char LAST_PRINTABLE = '~';

    private UrlCheck() {}

    /**
     * What a policy allows for one URL argument.
     *
     * @param schemes allowed schemes, a subset of {@code http} and {@code https}
     * @param hosts   allowed hosts; empty allows any public host
     * @param ports   allowed ports; empty allows only the scheme's default port
     */
    public record Rule(Set<String> schemes, List<HostPattern> hosts, Set<Integer> ports) {

        /** Allows {@code http} and {@code https} to any public host on its default port. */
        public static final Rule DEFAULT = new Rule(DEFAULT_PORTS.keySet(), List.of(), Set.of());

        /** Copies every component and rejects schemes ToolGate cannot reason about. */
        public Rule {
            schemes = Set.copyOf(schemes);
            hosts = List.copyOf(hosts);
            ports = Set.copyOf(ports);
            if (schemes.isEmpty() || !DEFAULT_PORTS.keySet().containsAll(schemes)) {
                throw new IllegalArgumentException("schemes must be a non-empty subset of " + DEFAULT_PORTS.keySet());
            }
        }
    }

    /**
     * Where an accepted URL points.
     *
     * @param scheme  {@code http} or {@code https}
     * @param host    the normalized host: lowercase, no trailing dot, an IPv6 address without brackets
     * @param port    the port the client will connect to
     * @param address the IP address, if the host is a literal
     */
    public record Target(String scheme, String host, int port, Optional<IpAddress> address) {
        public Target {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(address, "address");
        }
    }

    /** The outcome of a check. */
    public sealed interface Result permits Accepted, Rejected {}

    /**
     * The URL may be used.
     *
     * @param target where it points
     */
    public record Accepted(Target target) implements Result {}

    /**
     * The URL must not be used.
     *
     * @param reason why, for the audit trail
     */
    public record Rejected(String reason) implements Result {}

    /**
     * Checks one URL against one rule.
     *
     * @param url  the argument, exactly as the model wrote it
     * @param rule what the policy allows
     * @return {@link Accepted} with the target, or {@link Rejected} with the reason
     */
    public static Result check(String url, Rule rule) {
        Optional<String> badCharacter = badCharacter(url);
        if (badCharacter.isPresent()) {
            return new Rejected(badCharacter.get());
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException unparseable) {
            return new Rejected("not a valid URI: " + unparseable.getReason());
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawAuthority() == null) {
            return new Rejected("not an absolute URL with a host");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!rule.schemes().contains(scheme)) {
            return new Rejected("scheme '" + scheme + "' is not allowed");
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority().indexOf('@') >= 0) {
            return new Rejected("user info in a URL is not allowed");
        }
        if (uri.getRawAuthority().indexOf('%') >= 0) {
            return new Rejected("percent-encoded hosts are not allowed");
        }
        if (uri.getHost() == null) {
            return new Rejected("host '" + uri.getRawAuthority() + "' is not a valid host name");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : DEFAULT_PORTS.get(scheme);
        if (rule.ports().isEmpty() ? port != DEFAULT_PORTS.get(scheme) : !rule.ports().contains(port)) {
            return new Rejected("port " + port + " is not allowed");
        }
        return switch (checkHost(uri.getHost().toLowerCase(Locale.ROOT), rule)) {
            case HostAccepted(String host, Optional<IpAddress> address) ->
                    new Accepted(new Target(scheme, host, port, address));
            case HostRejected(String reason) -> new Rejected(reason);
        };
    }

    private sealed interface HostResult permits HostAccepted, HostRejected {}

    private record HostAccepted(String host, Optional<IpAddress> address) implements HostResult {}

    private record HostRejected(String reason) implements HostResult {}

    private static HostResult checkHost(String rawHost, Rule rule) {
        if (rawHost.startsWith("[")) {
            String literal = rawHost.substring(1, rawHost.length() - 1);
            return switch (IpLiterals.parseIpv6(literal)) {
                case IpLiterals.Address(IpAddress address) -> checkAddress(literal, address, rule);
                case IpLiterals.Malformed malformed -> new HostRejected(malformed.reason());
                case IpLiterals.NotAnAddress name -> new HostRejected("'" + literal + "' is not an IPv6 address");
            };
        }
        String host = withoutTrailingDot(rawHost);
        return switch (IpLiterals.parseIpv4Host(host)) {
            case IpLiterals.Address(IpAddress address) -> checkAddress(host, address, rule);
            case IpLiterals.Malformed malformed -> new HostRejected(malformed.reason());
            case IpLiterals.NotAnAddress name -> checkName(host, rule);
        };
    }

    private static HostResult checkAddress(String host, IpAddress address, Rule rule) {
        Optional<SpecialAddresses.Range> special = SpecialAddresses.find(address);
        if (special.isPresent()) {
            return new HostRejected("address " + address + " is " + special.get().name() + " (" + special.get().block() + ")");
        }
        // Host patterns are names, so a policy that lists hosts never allows a bare address.
        return rule.hosts().isEmpty()
                ? new HostAccepted(host, Optional.of(address))
                : new HostRejected("address " + address + " is not one of the allowed hosts");
    }

    private static HostResult checkName(String host, Rule rule) {
        Optional<String> problem = HostNames.invalidity(host).or(() -> HostNames.internalReason(host));
        if (problem.isPresent()) {
            return new HostRejected(problem.get());
        }
        if (!rule.hosts().isEmpty() && rule.hosts().stream().noneMatch(pattern -> pattern.matches(host))) {
            return new HostRejected("host '" + host + "' is not one of the allowed hosts " + rule.hosts());
        }
        return new HostAccepted(host, Optional.empty());
    }

    /** One trailing dot means the same host; two, or a lone dot, are rejected as invalid names later. */
    private static String withoutTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static Optional<String> badCharacter(String url) {
        for (int index = 0; index < url.length(); index++) {
            char c = url.charAt(index);
            if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE) {
                return Optional.of(String.format("character U+%04X at position %d is not printable ASCII", (int) c, index));
            }
            if (c == '\\') {
                return Optional.of("backslash at position " + index + "; some clients read it as '/'");
            }
        }
        return Optional.empty();
    }
}
