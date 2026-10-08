package io.toolgate.validate;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Recognizes IP address literals in URL hosts, without ever resolving a name.
 *
 * <p>Only canonical forms are accepted. Resolvers and HTTP clients also accept legacy IPv4 forms
 * such as {@code 2852039166}, {@code 0251.0376.0251.0376} or {@code 169.254.43518}, all of which
 * mean {@code 169.254.169.254}. Rather than reproduce every parser's reading of them, a host that
 * looks numeric but is not a canonical dotted quad is rejected: no legitimate tool call needs one.
 */
public final class IpLiterals {

    private static final int IPV4_PARTS = 4;
    private static final int MAX_OCTET = 255;
    private static final int IPV6_GROUPS = 8;
    private static final int MAX_GROUP_DIGITS = 4;

    private IpLiterals() {}

    /** What a host turned out to be. */
    public sealed interface Parsed permits Address, NotAnAddress, Malformed {}

    /**
     * The host is an IP literal in canonical form.
     *
     * @param address the address it denotes
     */
    public record Address(IpAddress address) implements Parsed {
        public Address {
            Objects.requireNonNull(address, "address");
        }
    }

    /** The host is a name, not an address. */
    public record NotAnAddress() implements Parsed {}

    /**
     * The host is, or looks like, an address, but not in an accepted form.
     *
     * @param reason why it was rejected
     */
    public record Malformed(String reason) implements Parsed {
        public Malformed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final NotAnAddress NOT_AN_ADDRESS = new NotAnAddress();

    /**
     * Classifies a host written without brackets.
     *
     * <p>As in the WHATWG URL standard, a host whose last label is numeric (decimal digits, or
     * {@code 0x} with hex digits) is an IPv4 address, because that is how clients will read it.
     *
     * @param host the host, lowercase, without a trailing dot
     * @return the address, {@link NotAnAddress} for a name, or {@link Malformed}
     */
    public static Parsed parseIpv4Host(String host) {
        // Like WHATWG, look past one empty final label, so "1.2.3." counts as numeric and is rejected.
        String labels = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String lastLabel = labels.substring(labels.lastIndexOf('.') + 1);
        if (!looksNumeric(lastLabel)) {
            return NOT_AN_ADDRESS;
        }
        OptionalInt bits = canonicalIpv4(host);
        return bits.isPresent()
                ? new Address(new IpAddress.V4(bits.getAsInt()))
                : new Malformed("IPv4 address '" + host + "' is not in canonical dotted-quad form");
    }

    /**
     * Parses the text between the brackets of an IPv6 host.
     *
     * @param text the address text, without brackets
     * @return the address, or {@link Malformed}; never {@link NotAnAddress}
     */
    public static Parsed parseIpv6(String text) {
        if (text.indexOf('%') >= 0) {
            // A zone ID names a local network interface: only meaningful for link-local targets.
            return new Malformed("IPv6 zone IDs are not allowed");
        }
        Optional<long[]> parsed = ipv6Groups(text);
        if (parsed.isEmpty()) {
            return new Malformed("'" + text + "' is not a valid IPv6 address");
        }
        long[] groups = parsed.get();
        long high = 0;
        long low = 0;
        for (int index = 0; index < IPV6_GROUPS; index++) {
            if (index < IPV6_GROUPS / 2) {
                high = (high << 16) | groups[index];
            } else {
                low = (low << 16) | groups[index];
            }
        }
        return new Address(new IpAddress.V6(high, low));
    }

    private static boolean looksNumeric(String label) {
        if (label.isEmpty()) {
            return false;
        }
        if (label.startsWith("0x")) {
            return label.chars().skip(2).allMatch(c -> hexValue((char) c) >= 0);
        }
        return label.chars().allMatch(IpLiterals::isAsciiDigit);
    }

    /** The address bits of a canonical dotted quad, or empty if the text is anything else. */
    private static OptionalInt canonicalIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != IPV4_PARTS) {
            return OptionalInt.empty();
        }
        int bits = 0;
        for (String part : parts) {
            int octet = canonicalOctet(part);
            if (octet < 0) {
                return OptionalInt.empty();
            }
            bits = (bits << 8) | octet;
        }
        return OptionalInt.of(bits);
    }

    /** 0 to 255 in decimal without leading zeros, or -1. Leading zeros mean octal to some parsers. */
    private static int canonicalOctet(String part) {
        if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')
                || !part.chars().allMatch(IpLiterals::isAsciiDigit)) {
            return -1;
        }
        int value = Integer.parseInt(part);
        return value <= MAX_OCTET ? value : -1;
    }

    /** The eight 16-bit groups, or empty if the text is not RFC 4291 text form. */
    private static Optional<long[]> ipv6Groups(String text) {
        int compression = text.indexOf("::");
        if (compression >= 0 && text.indexOf("::", compression + 1) >= 0) {
            return Optional.empty();
        }
        String head = compression >= 0 ? text.substring(0, compression) : text;
        String tail = compression >= 0 ? text.substring(compression + 2) : "";
        Optional<long[]> headGroups = groupList(head, compression < 0);
        Optional<long[]> tailGroups = groupList(tail, true);
        if (headGroups.isEmpty() || tailGroups.isEmpty()) {
            return Optional.empty();
        }
        long[] first = headGroups.get();
        long[] last = tailGroups.get();
        int written = first.length + last.length;
        if (compression < 0 ? written != IPV6_GROUPS : written >= IPV6_GROUPS) {
            return Optional.empty();
        }
        long[] groups = new long[IPV6_GROUPS];
        System.arraycopy(first, 0, groups, 0, first.length);
        System.arraycopy(last, 0, groups, IPV6_GROUPS - last.length, last.length);
        return Optional.of(groups);
    }

    /**
     * Parses colon-separated groups. The last group may be a dotted quad, which fills two groups,
     * but only where the address ends: {@code allowTrailingIpv4} is false for the part before "::".
     */
    private static Optional<long[]> groupList(String part, boolean allowTrailingIpv4) {
        if (part.isEmpty()) {
            return Optional.of(new long[0]);
        }
        String[] texts = part.split(":", -1);
        String last = texts[texts.length - 1];
        boolean endsInIpv4 = last.indexOf('.') >= 0;
        if (endsInIpv4 && !allowTrailingIpv4) {
            return Optional.empty();
        }
        long[] groups = new long[texts.length + (endsInIpv4 ? 1 : 0)];
        for (int index = 0; index < texts.length - (endsInIpv4 ? 1 : 0); index++) {
            int group = hexGroup(texts[index]);
            if (group < 0) {
                return Optional.empty();
            }
            groups[index] = group;
        }
        if (endsInIpv4) {
            OptionalInt bits = canonicalIpv4(last);
            if (bits.isEmpty()) {
                return Optional.empty();
            }
            groups[groups.length - 2] = bits.getAsInt() >>> 16;
            groups[groups.length - 1] = bits.getAsInt() & 0xffff;
        }
        return Optional.of(groups);
    }

    private static int hexGroup(String text) {
        if (text.isEmpty() || text.length() > MAX_GROUP_DIGITS) {
            return -1;
        }
        int value = 0;
        for (int index = 0; index < text.length(); index++) {
            int digit = hexValue(text.charAt(index));
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /** ASCII only: {@code Character.digit} would also accept digits from other scripts. */
    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }
}
