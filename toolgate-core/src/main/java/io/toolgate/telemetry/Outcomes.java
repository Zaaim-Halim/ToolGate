package io.toolgate.telemetry;

import io.toolgate.api.Decision;

/** The names this package gives decisions, the same as the audit log's. */
final class Outcomes {

    private Outcomes() {}

    static String name(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> "allow";
            case Decision.Deny deny -> "deny";
            case Decision.Defer defer -> "defer";
            case Decision.Downgrade downgrade -> "downgrade";
            case Decision.Terminate terminate -> "terminate";
        };
    }

    /** Whether the decision keeps the requested tool from running at all. */
    static boolean refuses(Decision decision) {
        return decision instanceof Decision.Deny || decision instanceof Decision.Terminate;
    }
}
