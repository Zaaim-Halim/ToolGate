package io.toolgate.validate;

import io.toolgate.spi.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Checks the addresses a host name resolves to, failing closed on anything unexpected. */
public final class ResolvedAddresses {

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;

    private ResolvedAddresses() {}

    /**
     * Why a name must not be used, after resolving it.
     *
     * @param resolver the application's resolver
     * @param host     a lowercase host name
     * @return the problem, or empty if every address is ordinary public unicast
     */
    public static Optional<String> problem(HostResolver resolver, String host) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException | RuntimeException failure) {
            return Optional.of("host '" + host + "' could not be resolved: " + failure);
        }
        if (addresses == null || addresses.isEmpty()) {
            return Optional.of("host '" + host + "' resolved to no address");
        }
        for (InetAddress resolved : addresses) {
            IpAddress address = toIpAddress(Objects.requireNonNull(resolved, "resolved address"));
            Optional<SpecialAddresses.Range> special = SpecialAddresses.find(address);
            if (special.isPresent()) {
                return Optional.of("host '" + host + "' resolves to " + address + ", which is "
                        + special.get().name() + " (" + special.get().block() + ")");
            }
        }
        return Optional.empty();
    }

    /**
     * Why an address must not be connected to.
     *
     * @param resolved a resolved address
     * @return the special-purpose range it is in, described, or empty if it is ordinary public unicast
     */
    public static Optional<String> problem(InetAddress resolved) {
        IpAddress address = toIpAddress(Objects.requireNonNull(resolved, "address"));
        return SpecialAddresses.find(address).map(special -> address + " is " + special.name() + " (" + special.block() + ")");
    }

    private static IpAddress toIpAddress(InetAddress resolved) {
        ByteBuffer bytes = ByteBuffer.wrap(resolved.getAddress());
        return switch (bytes.remaining()) {
            case IPV4_BYTES -> new IpAddress.V4(bytes.getInt());
            case IPV6_BYTES -> new IpAddress.V6(bytes.getLong(), bytes.getLong());
            default -> throw new IllegalStateException("address of " + bytes.remaining() + " bytes");
        };
    }
}
