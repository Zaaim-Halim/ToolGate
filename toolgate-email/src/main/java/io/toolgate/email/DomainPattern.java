package io.toolgate.email;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A recipient domain a policy allows: {@code mycompany.com} exactly, or {@code *.mycompany.com} for any
 * subdomain at any depth but not {@code mycompany.com} itself; the same semantics as URL host patterns.
 */
record DomainPattern(String domain, boolean wildcard) {

    private static final String WILDCARD_PREFIX = "*.";
    private static final int MAX_DOMAIN = 253;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    DomainPattern {
        if (!isDomain(domain)) {
            throw new IllegalArgumentException("'" + domain + "' is not a domain name such as mycompany.com");
        }
    }

    static DomainPattern parse(String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        return lower.startsWith(WILDCARD_PREFIX)
                ? new DomainPattern(lower.substring(WILDCARD_PREFIX.length()), true)
                : new DomainPattern(lower, false);
    }

    /**
     * Whether a domain is lowercase ASCII LDH labels, at least two of them, with no trailing dot. Unicode
     * domains are not domains here: their {@code xn--} form is, so a look-alike letter cannot pass.
     */
    static boolean isDomain(String domain) {
        if (domain.length() > MAX_DOMAIN) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    boolean matches(String lowerDomain) {
        return wildcard ? lowerDomain.endsWith("." + domain) : lowerDomain.equals(domain);
    }

    @Override
    public String toString() {
        return wildcard ? WILDCARD_PREFIX + domain : domain;
    }
}
