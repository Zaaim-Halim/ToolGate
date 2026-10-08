package io.toolgate.validate;

import java.util.Locale;
import java.util.Objects;

/**
 * A host a policy allows: an exact name such as {@code docs.mycompany.com}, or {@code *.suppliers.example}
 * for any subdomain of {@code suppliers.example}, at any depth, but not {@code suppliers.example} itself.
 *
 * <p>Matching is by whole labels, so {@code *.mycompany.com} never matches {@code evilmycompany.com}.
 *
 * @param name     the exact host, or the suffix after {@code *.}
 * @param wildcard whether subdomains of {@code name} match instead of {@code name}
 */
public record HostPattern(String name, boolean wildcard) {

    private static final String WILDCARD_PREFIX = "*.";

    /** Requires a valid lowercase host name that is not an IP address. */
    public HostPattern {
        Objects.requireNonNull(name, "name");
        HostNames.requireValid(name);
        if (!(IpLiterals.parseIpv4Host(name) instanceof IpLiterals.NotAnAddress)) {
            throw new IllegalArgumentException("host pattern '" + name + "' must be a name, not an address");
        }
    }

    /**
     * Parses a pattern as written in a policy.
     *
     * @param pattern such as {@code docs.mycompany.com} or {@code *.suppliers.example}; case is ignored
     * @return the pattern
     * @throws IllegalArgumentException if the pattern is not a valid name, or uses {@code *} anywhere else
     */
    public static HostPattern parse(String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        return lower.startsWith(WILDCARD_PREFIX)
                ? new HostPattern(lower.substring(WILDCARD_PREFIX.length()), true)
                : new HostPattern(lower, false);
    }

    /**
     * Whether a normalized host matches.
     *
     * @param host lowercase, without a trailing dot
     * @return {@code true} if it is this name, or for a wildcard, a subdomain of it
     */
    public boolean matches(String host) {
        return wildcard ? host.endsWith("." + name) : host.equals(name);
    }

    @Override
    public String toString() {
        return wildcard ? WILDCARD_PREFIX + name : name;
    }
}
