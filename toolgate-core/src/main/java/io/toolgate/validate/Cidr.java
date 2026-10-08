package io.toolgate.validate;

import java.util.Objects;

/**
 * A block of addresses: a network address and the number of leading bits that identify it.
 *
 * @param network      the first address of the block; bits past the prefix must be zero
 * @param prefixLength how many leading bits must match
 */
public record Cidr(IpAddress network, int prefixLength) {

    /** Rejects prefixes outside the family's range and networks with host bits set. */
    public Cidr {
        Objects.requireNonNull(network, "network");
        if (prefixLength < 0 || prefixLength > network.bitLength()) {
            throw new IllegalArgumentException("prefix /" + prefixLength + " out of range for " + network);
        }
        if (!matches(network, network, prefixLength) || !network.equals(masked(network, prefixLength))) {
            throw new IllegalArgumentException(network + "/" + prefixLength + " has bits set past the prefix");
        }
    }

    /**
     * Parses {@code address/prefix}, with an IPv6 address unbracketed.
     *
     * @param text such as {@code 169.254.0.0/16} or {@code fc00::/7}
     * @return the block
     * @throws IllegalArgumentException if the text is not a canonical block
     */
    public static Cidr parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("'" + text + "' has no prefix length");
        }
        String address = text.substring(0, slash);
        IpLiterals.Parsed parsed = address.indexOf(':') >= 0
                ? IpLiterals.parseIpv6(address)
                : IpLiterals.parseIpv4Host(address);
        if (!(parsed instanceof IpLiterals.Address(IpAddress network))) {
            throw new IllegalArgumentException("'" + address + "' is not an IP address");
        }
        return new Cidr(network, Integer.parseInt(text.substring(slash + 1)));
    }

    /**
     * Whether an address lies in this block. Addresses of the other family never do.
     *
     * @param address the address
     * @return {@code true} if its first {@link #prefixLength} bits equal the network's
     */
    public boolean contains(IpAddress address) {
        return matches(network, address, prefixLength);
    }

    @Override
    public String toString() {
        return network + "/" + prefixLength;
    }

    private static boolean matches(IpAddress network, IpAddress address, int prefixLength) {
        return switch (network) {
            case IpAddress.V4 net -> address instanceof IpAddress.V4 v4
                    && (net.bits() & mask32(prefixLength)) == (v4.bits() & mask32(prefixLength));
            case IpAddress.V6 net -> address instanceof IpAddress.V6 v6
                    && (net.high() & highMask(prefixLength)) == (v6.high() & highMask(prefixLength))
                    && (net.low() & lowMask(prefixLength)) == (v6.low() & lowMask(prefixLength));
        };
    }

    private static IpAddress masked(IpAddress address, int prefixLength) {
        return switch (address) {
            case IpAddress.V4 v4 -> new IpAddress.V4(v4.bits() & mask32(prefixLength));
            case IpAddress.V6 v6 -> new IpAddress.V6(v6.high() & highMask(prefixLength), v6.low() & lowMask(prefixLength));
        };
    }

    // Java masks shift distances to the low five or six bits, so a shift by the full width is a no-op;
    // the zero-length cases are handled explicitly.

    private static int mask32(int prefixLength) {
        return prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
    }

    private static long highMask(int prefixLength) {
        if (prefixLength == 0) {
            return 0;
        }
        return prefixLength >= 64 ? -1L : -1L << (64 - prefixLength);
    }

    private static long lowMask(int prefixLength) {
        return prefixLength <= 64 ? 0 : -1L << (128 - prefixLength);
    }
}
