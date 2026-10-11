package io.toolgate.approvals.web;

import java.net.URI;
import java.util.Locale;

/**
 * Refuses a state-changing request that a page on another site could have sent: it must be JSON, carry
 * the header {@value #HEADER}, and, when the browser names its origin, come from this host. A form on
 * another site cannot set a custom header, and a script on another site would need CORS, which the API
 * never allows; so this holds even in an application that turned Spring Security's CSRF protection off.
 */
final class RequestGuard {

    /** The header every state-changing request carries. */
    static final String HEADER = "X-ToolGate-Request";

    private RequestGuard() {}

    /**
     * Checks a state-changing request.
     *
     * @param contentType the request's {@code Content-Type}, or null
     * @param marker      the {@value #HEADER} header, or null
     * @param origin      the {@code Origin} header, or null
     * @param host        the request's {@code Host} header, as the browser addressed it, port optional; else the
     *                    server's host and port
     * @throws ApiException 403 if any check fails
     */
    static void check(String contentType, String marker, String origin, String host) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw ApiException.forbidden("send JSON");
        }
        if (!"1".equals(marker)) {
            throw ApiException.forbidden("missing " + HEADER);
        }
        if (origin != null && !sameHost(origin, host)) {
            throw ApiException.forbidden("cross-origin request");
        }
    }

    /** Compares host and port, filling in the scheme's default port on either side. */
    private static boolean sameHost(String origin, String host) {
        if (host == null || host.isBlank() || "null".equals(origin)) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null) {
                return false;
            }
            int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            String originAuthority = uri.getHost() + ":" + (uri.getPort() == -1 ? defaultPort : uri.getPort());
            // An IPv6 host is bracketed, so a port follows the last colon only if it comes after the bracket.
            boolean hostHasPort = host.lastIndexOf(':') > host.lastIndexOf(']');
            String hostAuthority = hostHasPort ? host : host + ":" + defaultPort;
            return originAuthority.equalsIgnoreCase(hostAuthority);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
