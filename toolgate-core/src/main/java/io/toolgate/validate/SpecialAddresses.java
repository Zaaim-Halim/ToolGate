package io.toolgate.validate;

import java.util.List;
import java.util.Optional;

/**
 * Addresses a tool call must never reach by default: anything that is not ordinary public unicast.
 *
 * <p>That covers private networks, loopback, link-local (AWS, GCP and Azure metadata at
 * {@code 169.254.169.254}), shared address space (Alibaba's metadata at {@code 100.100.100.200}),
 * IETF protocol assignments (Oracle's metadata at {@code 192.0.0.192}), unique-local IPv6 (AWS's
 * {@code fd00:ec2::254}), and every IPv6 form that carries an IPv4 address inside it, which could
 * reach an internal IPv4 host however the IPv4 address is checked.
 *
 * <p>IPv6 is checked by exclusion: anything outside global unicast {@code 2000::/3} is special,
 * so a newly assigned special range outside it is denied without a code change. Sources: the IANA
 * IPv4 and IPv6 Special-Purpose Address Registries.
 */
public final class SpecialAddresses {

    /**
     * A named special-purpose block.
     *
     * @param block the addresses
     * @param name  what the block is, for denial reasons in the audit trail
     */
    public record Range(Cidr block, String name) {}

    private static final Cidr GLOBAL_UNICAST_V6 = Cidr.parse("2000::/3");

    // More specific blocks come first, so the reason names the narrowest one.
    private static final List<Range> RANGES = List.of(
            range("0.0.0.0/8", "this network"),
            range("10.0.0.0/8", "private network"),
            range("100.64.0.0/10", "shared address space"),
            range("127.0.0.0/8", "loopback"),
            range("169.254.0.0/16", "link-local, including cloud metadata"),
            range("172.16.0.0/12", "private network"),
            range("192.0.0.0/24", "IETF protocol assignments"),
            range("192.0.2.0/24", "documentation"),
            range("192.88.99.0/24", "6to4 relay anycast"),
            range("192.168.0.0/16", "private network"),
            range("198.18.0.0/15", "benchmarking"),
            range("198.51.100.0/24", "documentation"),
            range("203.0.113.0/24", "documentation"),
            range("224.0.0.0/4", "multicast"),
            range("240.0.0.0/4", "reserved, including broadcast"),

            range("::/128", "unspecified"),
            range("::1/128", "loopback"),
            range("::ffff:0:0/96", "IPv4-mapped"),
            range("::ffff:0:0:0/96", "IPv4-translated"),
            range("::/96", "IPv4-compatible"),
            range("64:ff9b::/96", "NAT64"),
            range("64:ff9b:1::/48", "local-use NAT64"),
            range("100::/64", "discard-only"),
            range("2001::/32", "Teredo"),
            range("2001:db8::/32", "documentation"),
            range("2001::/23", "IETF protocol assignments"),
            range("2002::/16", "6to4"),
            range("3fff::/20", "documentation"),
            range("5f00::/16", "segment routing"),
            range("fc00::/7", "unique local, including cloud metadata"),
            range("fe80::/10", "link-local"),
            range("fec0::/10", "site-local"),
            range("ff00::/8", "multicast"));

    private SpecialAddresses() {}

    /**
     * The special-purpose block an address belongs to.
     *
     * @param address the address
     * @return the narrowest named block, a catch-all for IPv6 outside global unicast, or empty for
     *         an ordinary public address
     */
    public static Optional<Range> find(IpAddress address) {
        for (Range range : RANGES) {
            if (range.block().contains(address)) {
                return Optional.of(range);
            }
        }
        if (address instanceof IpAddress.V6 && !GLOBAL_UNICAST_V6.contains(address)) {
            return Optional.of(new Range(GLOBAL_UNICAST_V6, "outside global unicast"));
        }
        return Optional.empty();
    }

    private static Range range(String block, String name) {
        return new Range(Cidr.parse(block), name);
    }
}
