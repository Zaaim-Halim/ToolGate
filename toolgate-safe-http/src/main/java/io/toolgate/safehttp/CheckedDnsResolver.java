package io.toolgate.safehttp;

import io.toolgate.spi.HostResolver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Resolves a host once and refuses it if any address is special-purpose; the connection then goes to
 * exactly the addresses returned here, so the address checked is the address connected to.
 */
final class CheckedDnsResolver implements DnsResolver {

    private final HostResolver resolver;
    private final Function<InetAddress, Optional<String>> addressCheck;

    CheckedDnsResolver(HostResolver resolver, Function<InetAddress, Optional<String>> addressCheck) {
        this.resolver = resolver;
        this.addressCheck = addressCheck;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<InetAddress> addresses = resolver.resolve(host);
        if (addresses == null || addresses.isEmpty()) {
            throw new UnknownHostException(host + " resolved to no address");
        }
        for (InetAddress address : addresses) {
            Optional<String> problem = addressCheck.apply(address);
            if (problem.isPresent()) {
                // One bad address refuses the host: the client might connect to any of them.
                throw new RefusedDestinationException("SafeHttp refused " + host + ": " + problem.get());
            }
        }
        return addresses.toArray(InetAddress[]::new);
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return List.of(resolve(host)).stream().map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Never asks the network again: a second lookup could answer differently.
        return host;
    }
}
