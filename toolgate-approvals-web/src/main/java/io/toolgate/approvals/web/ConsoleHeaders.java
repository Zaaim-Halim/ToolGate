package io.toolgate.approvals.web;

import java.util.Map;

/**
 * The headers every console and API response carries: the page runs only its own script and styles,
 * cannot be framed (so nobody can trick a reviewer into clicking Approve inside another site), and
 * nothing it shows is cached, since it shows personal data.
 */
final class ConsoleHeaders {

    /** Where the console and its API live. */
    static final String PATH_PREFIX = "/toolgate/";

    static final Map<String, String> HEADERS = Map.of(
            "Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                    + " connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            "X-Frame-Options", "DENY",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer",
            "Cache-Control", "no-store",
            "Cross-Origin-Opener-Policy", "same-origin",
            "Cross-Origin-Resource-Policy", "same-origin");

    private ConsoleHeaders() {}
}
