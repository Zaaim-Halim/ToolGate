package io.toolgate.validate;

import java.util.List;
import java.util.Optional;

/** Rules for host names, shared by URL checks and policy patterns. */
final class HostNames {

    private static final int MAX_NAME_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;

    /**
     * Suffixes that only ever name hosts inside a network (RFC 6761, RFC 6762, RFC 8375 and the
     * reserved {@code .internal}, which covers {@code metadata.google.internal}).
     */
    private static final List<String> INTERNAL_SUFFIXES = List.of("localhost", "local", "internal", "home.arpa");

    private HostNames() {}

    /** Throws unless {@code name} is lowercase LDH labels within the DNS length limits. */
    static void requireValid(String name) {
        invalidity(name).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
    }

    /** Why a lowercase name is not a valid host name, if it is not. */
    static Optional<String> invalidity(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return Optional.of("host name length must be 1 to " + MAX_NAME_LENGTH);
        }
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return Optional.of("host '" + name + "' has an empty or overlong label");
            }
            if (label.startsWith("-") || label.endsWith("-")
                    || !label.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return Optional.of("host '" + name + "' has a label that is not letters, digits and inner hyphens");
            }
        }
        return Optional.empty();
    }

    /**
     * Why a valid name is internal by definition, if it is.
     *
     * <p>A single-label name such as {@code jenkins} is internal too: resolvers complete it with
     * the network's search domains.
     */
    static Optional<String> internalReason(String name) {
        if (name.indexOf('.') < 0) {
            return Optional.of("single-label host '" + name + "' resolves through local search domains");
        }
        for (String suffix : INTERNAL_SUFFIXES) {
            if (name.equals(suffix) || name.endsWith("." + suffix)) {
                return Optional.of("host '" + name + "' is under ." + suffix + ", which is internal by definition");
            }
        }
        return Optional.empty();
    }
}
