package io.toolgate.test;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import java.util.Arrays;
import java.util.Set;

/** Reads the principal shorthand of {@link ToolGateTest#principal()}. */
final class Principals {

    private Principals() {}

    /**
     * {@code kind:id[@tenant][#role,role]}, such as {@code service:invoice-processor@acme} or
     * {@code user:maria@acme#support,finance}.
     */
    static Principal parse(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("principal must read kind:id[@tenant][#roles], was " + text);
        }
        Principal.Kind kind = switch (text.substring(0, colon)) {
            case "user" -> Principal.Kind.USER;
            case "service" -> Principal.Kind.SERVICE;
            default -> throw new IllegalArgumentException("principal kind must be user or service, was " + text);
        };
        String rest = text.substring(colon + 1);
        Set<String> roles = Set.of();
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            roles = Set.copyOf(Arrays.asList(rest.substring(hash + 1).split(",")));
            rest = rest.substring(0, hash);
        }
        int at = rest.indexOf('@');
        Tenant tenant = at < 0 ? Tenant.none() : Tenant.of(rest.substring(at + 1));
        String id = at < 0 ? rest : rest.substring(0, at);
        return new Principal(kind, id, tenant, roles);
    }
}
