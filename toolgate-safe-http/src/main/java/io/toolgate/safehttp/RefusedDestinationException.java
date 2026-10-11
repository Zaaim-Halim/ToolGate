package io.toolgate.safehttp;

import java.io.Serial;
import java.net.UnknownHostException;

/**
 * SafeHttp refused a destination: its URL breaks the rules, or its host resolves to a special-purpose
 * address. Nothing was sent. An {@link UnknownHostException}, so HTTP clients and tools that handle a
 * host they cannot reach handle this too.
 */
public final class RefusedDestinationException extends UnknownHostException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param reason why, naming the destination
     */
    public RefusedDestinationException(String reason) {
        super(reason);
    }
}
