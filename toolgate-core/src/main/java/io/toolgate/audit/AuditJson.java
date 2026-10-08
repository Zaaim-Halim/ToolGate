package io.toolgate.audit;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.TaintSource;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes an audit event as one line of JSON, with no library.
 *
 * <p>Reasons and IDs can contain text the model wrote, so escaping is strict: every control
 * character, the Unicode line separators and every surrogate are escaped. A value can never end the
 * line, break out of its string, or make the line invalid UTF-8.
 */
public final class AuditJson {

    private static final int UNLIMITED = Integer.MAX_VALUE;

    private AuditJson() {}

    /**
     * Formats one event.
     *
     * @param event the event
     * @return a single line of JSON, without a line break
     */
    public static String format(AuditEvent event) {
        return switch (event) {
            case AuditEvent.Decided decided -> decided(decided);
            case AuditEvent.PolicyLoaded loaded -> new Writer()
                    .field("event", "toolgate.policy")
                    .field("time", loaded.time().toString())
                    .field("action", "loaded")
                    .field("policyHash", loaded.policyHash())
                    .field("mode", loaded.mode().name().toLowerCase(Locale.ROOT))
                    .number("agents", loaded.agents())
                    .close();
            case AuditEvent.Declassified declassified -> new Writer()
                    .field("event", "toolgate.declassify")
                    .field("time", declassified.time().toString())
                    .field("session", declassified.sessionId())
                    .field("label", declassified.label().name())
                    .field("justification", declassified.justification())
                    .close();
        };
    }

    private static String decided(AuditEvent.Decided event) {
        var json = new Writer()
                .field("event", "toolgate.decision")
                .field("time", event.time().toString())
                .field("callId", event.callId())
                .field("session", event.sessionId())
                .field("agent", event.agentId())
                .raw("principal", principal(event.principal()))
                .field("tool", event.tool().toString())
                .raw("args", strings(event.args()))
                .field("decision", name(event.applied()))
                .field("rule", event.applied().ruleId());
        reason(event.applied()).ifPresent(reason -> json.field("reason", reason));
        if (event.applied() instanceof Decision.Downgrade downgrade) {
            json.field("downgradedTo", downgrade.safeTool().toString());
        }
        json.field("computed", name(event.computed()))
                .field("computedRule", event.computed().ruleId());
        if (!event.taint().isEmpty()) {
            json.raw("because", because(event.taint()));
        }
        return json.field("mode", event.mode().name().toLowerCase(Locale.ROOT))
                .raw("budget", new Writer()
                        .number("toolCalls", event.toolCalls())
                        .raw("limit", event.toolCallLimit() == UNLIMITED ? "null" : Integer.toString(event.toolCallLimit()))
                        .close())
                .field("policyHash", event.policyHash())
                .close();
    }

    private static String principal(Principal principal) {
        String tenant = switch (principal.tenant()) {
            case Tenant.Scoped(String id) -> quote(id);
            case Tenant.Unscoped unscoped -> "null";
        };
        var roles = new StringBuilder("[");
        principal.roles().stream().sorted().forEach(role -> {
            if (roles.length() > 1) {
                roles.append(',');
            }
            roles.append(quote(role));
        });
        return new Writer()
                .field("kind", principal.kind().name())
                .field("id", principal.id())
                .raw("tenant", tenant)
                .raw("roles", roles.append(']').toString())
                .close();
    }

    /** The taint behind a decision, in label order, as [{"label", "addedBy", "tool"}, ...]. */
    private static String because(Map<Label, TaintSource> taint) {
        var json = new StringBuilder("[");
        taint.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(new Writer()
                    .field("label", entry.getKey().name())
                    .field("addedBy", entry.getValue().callId())
                    .field("tool", entry.getValue().tool().toString())
                    .close());
        });
        return json.append(']').toString();
    }

    private static String strings(Map<String, String> values) {
        var json = new Writer();
        values.forEach(json::field);
        return json.close();
    }

    private static String name(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> "allow";
            case Decision.Deny deny -> "deny";
            case Decision.Defer defer -> "defer";
            case Decision.Downgrade downgrade -> "downgrade";
            case Decision.Terminate terminate -> "terminate";
        };
    }

    private static Optional<String> reason(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> Optional.empty();
            case Decision.Deny(String rule, String reason) -> Optional.of(reason);
            case Decision.Defer(String rule, String reason) -> Optional.of(reason);
            case Decision.Downgrade downgrade -> Optional.empty();
            case Decision.Terminate(String rule, String reason) -> Optional.of(reason);
        };
    }

    /**
     * A JSON string literal.
     *
     * @param value any text, including lone surrogates
     * @return the literal, quotes included, containing only escaped control characters
     */
    public static String quote(String value) {
        var out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '\u2028' || c == '\u2029'
                            || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Builds one JSON object, field by field, in the order written. */
    private static final class Writer {

        private final StringBuilder json = new StringBuilder("{");

        Writer field(String name, String value) {
            return raw(name, quote(value));
        }

        Writer number(String name, long value) {
            return raw(name, Long.toString(value));
        }

        Writer raw(String name, String json) {
            if (this.json.length() > 1) {
                this.json.append(',');
            }
            this.json.append(quote(name)).append(':').append(json);
            return this;
        }

        String close() {
            return json.append('}').toString();
        }
    }
}
