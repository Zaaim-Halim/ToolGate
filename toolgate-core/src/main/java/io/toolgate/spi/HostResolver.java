package io.toolgate.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves host names, so URL rules can also check the addresses a name points at.
 *
 * <p>Optional, and off by default: resolving during a check is blocking I/O, its answer can change
 * between the check and the connection (DNS rebinding), and it slows every checked call. The
 * reliable check is in SafeHttp, which connects to the address it checked. Use a resolver when tools
 * use their own HTTP clients and an early, best-effort check is worth the cost.
 *
 * <p>When set, a URL whose host is a name is denied if the name cannot be resolved, resolves to no
 * address, or resolves to any address in a special-purpose range.
 */
@FunctionalInterface
public interface HostResolver {

    /**
     * Resolves a host name. Implementations should apply their own timeout.
     *
     * @param host a lowercase host name, never an IP literal
     * @return every address the name resolves to
     * @throws UnknownHostException if the name cannot be resolved
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /**
     * The JVM's resolver, through {@link InetAddress#getAllByName}. Blocking, and cached by the JVM.
     *
     * @return the resolver
     */
    static HostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
