package io.toolgate.jdbc;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.BooleanValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ListValue;
import io.toolgate.api.NullValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.TaintSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns session events, decisions, principals and taint into JSON and back, through ArgValueJson.
 * Times are epoch microseconds. Anything unexpected on the way back throws IllegalArgumentException,
 * which the stores report as an unreadable row.
 */
final class Codec {

    private Codec() {}

    static String event(SessionEvent event) {
        return ArgValueJson.write(switch (event) {
            case SessionEvent.CallDecided decided -> object(
                    "type", text("CallDecided"),
                    "sessionId", text(decided.sessionId()),
                    "callId", text(decided.callId()),
                    "agentId", text(decided.agentId()),
                    "principal", principal(decided.principal()),
                    "tool", text(decided.tool().toString()),
                    "args", decided.args(),
                    "applied", decision(decided.applied()),
                    "computed", decision(decided.computed()),
                    "spent", amounts(decided.spent()),
                    "policyHash", text(decided.policyHash()),
                    "at", number(decided.at()));
            case SessionEvent.ResultRecorded recorded -> object(
                    "type", text("ResultRecorded"),
                    "sessionId", text(recorded.sessionId()),
                    "callId", text(recorded.callId()),
                    "tool", text(recorded.tool().toString()),
                    "succeeded", ArgValue.bool(recorded.succeeded()),
                    "withheld", ArgValue.bool(recorded.withheld()),
                    "labels", ArgValue.list(recorded.labels().stream().sorted().map(label -> text(label.name())).toList()),
                    "at", number(recorded.at()),
                    "trusted", fingerprints(recorded.trusted()));
            case SessionEvent.CallReleased released -> object(
                    "type", text("CallReleased"),
                    "sessionId", text(released.sessionId()),
                    "approvalId", text(released.approvalId()),
                    "callId", text(released.callId()),
                    "spent", amounts(released.spent()),
                    "at", number(released.at()));
            case SessionEvent.Declassified declassified -> object(
                    "type", text("Declassified"),
                    "sessionId", text(declassified.sessionId()),
                    "label", text(declassified.label().name()),
                    "justification", text(declassified.justification()),
                    "at", number(declassified.at()));
            case SessionEvent.CallUnresolved lost -> object(
                    "type", text("CallUnresolved"),
                    "sessionId", text(lost.sessionId()),
                    "approvalId", text(lost.approvalId()),
                    "tool", text(lost.tool().toString()),
                    "at", number(lost.at()));
            case SessionEvent.CallResolved known -> object(
                    "type", text("CallResolved"),
                    "sessionId", text(known.sessionId()),
                    "approvalId", text(known.approvalId()),
                    "at", number(known.at()));
        });
    }

    static SessionEvent event(String json) {
        ObjectValue event = asObject(ArgValueJson.read(json));
        return switch (string(event, "type")) {
            case "CallDecided" -> new SessionEvent.CallDecided(string(event, "sessionId"), string(event, "callId"),
                    string(event, "agentId"), principal(field(event, "principal")), ToolId.parse(string(event, "tool")),
                    asObject(field(event, "args")), decision(field(event, "applied")), decision(field(event, "computed")),
                    amounts(field(event, "spent")), string(event, "policyHash"), instant(event, "at"));
            case "CallReleased" -> new SessionEvent.CallReleased(string(event, "sessionId"), string(event, "approvalId"),
                    string(event, "callId"), amounts(field(event, "spent")), instant(event, "at"));
            case "ResultRecorded" -> new SessionEvent.ResultRecorded(string(event, "sessionId"), string(event, "callId"),
                    ToolId.parse(string(event, "tool")), bool(event, "succeeded"), bool(event, "withheld"),
                    labels(field(event, "labels")), instant(event, "at"),
                    event.field("trusted").map(Codec::fingerprints).orElse(Map.of()));
            case "Declassified" -> new SessionEvent.Declassified(string(event, "sessionId"),
                    Label.valueOf(string(event, "label")), string(event, "justification"), instant(event, "at"));
            case "CallUnresolved" -> new SessionEvent.CallUnresolved(string(event, "sessionId"),
                    string(event, "approvalId"), ToolId.parse(string(event, "tool")), instant(event, "at"));
            case "CallResolved" -> new SessionEvent.CallResolved(string(event, "sessionId"),
                    string(event, "approvalId"), instant(event, "at"));
            default -> throw new IllegalArgumentException("unknown event type " + string(event, "type"));
        };
    }

    static String taint(Map<Label, TaintSource> taint) {
        var fields = new LinkedHashMap<String, ArgValue>();
        taint.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> fields.put(entry.getKey().name(),
                object("callId", text(entry.getValue().callId()), "tool", text(entry.getValue().tool().toString()))));
        return ArgValueJson.write(ArgValue.object(fields));
    }

    static Map<Label, TaintSource> taint(String json) {
        var taint = new EnumMap<Label, TaintSource>(Label.class);
        asObject(ArgValueJson.read(json)).fields().forEach((label, source) -> taint.put(Label.valueOf(label),
                new TaintSource(string(asObject(source), "callId"), ToolId.parse(string(asObject(source), "tool")))));
        return taint;
    }

    /**
     * A session's trusted values: fingerprints by field, as sorted lists, never the values themselves, and
     * whether the session has closed to new ones.
     */
    static String trusted(Map<String, Set<String>> trusted, boolean closed) {
        return ArgValueJson.write(object("closed", ArgValue.bool(closed), "fields", fingerprints(trusted)));
    }

    /** Reads {@link #trusted(Map, boolean)}: the fingerprints, and whether trust is closed. */
    static Map.Entry<Map<String, Set<String>>, Boolean> trusted(String json) {
        ObjectValue state = asObject(ArgValueJson.read(json));
        return Map.entry(fingerprints(field(state, "fields")), bool(state, "closed"));
    }

    private static ArgValue fingerprints(Map<String, Set<String>> trusted) {
        var fields = new LinkedHashMap<String, ArgValue>();
        new TreeMap<>(trusted).forEach((key, values) ->
                fields.put(key, ArgValue.list(values.stream().sorted().map(ArgValue::text).toList())));
        return ArgValue.object(fields);
    }

    private static Map<String, Set<String>> fingerprints(ArgValue encoded) {
        var trusted = new TreeMap<String, Set<String>>();
        asObject(encoded).fields().forEach((key, values) -> {
            if (!(values instanceof ListValue list)) {
                throw new IllegalArgumentException("trusted values of " + key + " are not a list");
            }
            var fingerprints = new TreeSet<String>();
            list.elements().forEach(value -> {
                if (!(value instanceof TextValue(String fingerprint))) {
                    throw new IllegalArgumentException("a trusted value of " + key + " is not text");
                }
                fingerprints.add(fingerprint);
            });
            trusted.put(key, fingerprints);
        });
        return trusted;
    }

    static String totals(Map<String, BigDecimal> totals) {
        return ArgValueJson.write(amounts(totals));
    }

    static Map<String, BigDecimal> totals(String json) {
        return amounts(ArgValueJson.read(json));
    }

    static String unresolved(Map<String, ToolId> unresolved) {
        var fields = new LinkedHashMap<String, ArgValue>();
        new TreeMap<>(unresolved).forEach((approvalId, tool) -> fields.put(approvalId, text(tool.toString())));
        return ArgValueJson.write(ArgValue.object(fields));
    }

    static Map<String, ToolId> unresolved(String json) {
        var unresolved = new TreeMap<String, ToolId>();
        asObject(ArgValueJson.read(json)).fields().forEach((approvalId, tool) -> {
            if (!(tool instanceof TextValue(String name))) {
                throw new IllegalArgumentException("unresolved holds a non-string tool");
            }
            unresolved.put(approvalId, ToolId.parse(name));
        });
        return unresolved;
    }

    static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    private static ArgValue decision(Decision decision) {
        return switch (decision) {
            case Decision.Allow(String ruleId) -> object("kind", text("allow"), "ruleId", text(ruleId));
            case Decision.Deny(String ruleId, String reason) ->
                    object("kind", text("deny"), "ruleId", text(ruleId), "reason", text(reason));
            case Decision.Defer(String ruleId, String reason, String approvalId) -> object("kind", text("defer"),
                    "ruleId", text(ruleId), "reason", text(reason), "approvalId", text(approvalId));
            case Decision.Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) -> object("kind",
                    text("downgrade"), "ruleId", text(ruleId), "safeTool", text(safeTool.toString()), "safeArgs", safeArgs);
            case Decision.Terminate(String ruleId, String reason) ->
                    object("kind", text("terminate"), "ruleId", text(ruleId), "reason", text(reason));
        };
    }

    private static Decision decision(ArgValue value) {
        ObjectValue decision = asObject(value);
        String ruleId = string(decision, "ruleId");
        return switch (string(decision, "kind")) {
            case "allow" -> new Decision.Allow(ruleId);
            case "deny" -> new Decision.Deny(ruleId, string(decision, "reason"));
            case "defer" -> new Decision.Defer(ruleId, string(decision, "reason"), string(decision, "approvalId"));
            case "downgrade" -> new Decision.Downgrade(ruleId, ToolId.parse(string(decision, "safeTool")),
                    asObject(field(decision, "safeArgs")));
            case "terminate" -> new Decision.Terminate(ruleId, string(decision, "reason"));
            default -> throw new IllegalArgumentException("unknown decision kind " + string(decision, "kind"));
        };
    }

    private static ObjectValue amounts(Map<String, BigDecimal> amounts) {
        var fields = new LinkedHashMap<String, ArgValue>();
        amounts.forEach((ruleId, amount) -> fields.put(ruleId, ArgValue.number(amount)));
        return new ObjectValue(fields);
    }

    private static Map<String, BigDecimal> amounts(ArgValue value) {
        var amounts = new LinkedHashMap<String, BigDecimal>();
        asObject(value).fields().forEach((ruleId, amount) -> {
            if (!(amount instanceof NumberValue(BigDecimal number))) {
                throw new IllegalArgumentException("total " + ruleId + " is not a number");
            }
            amounts.put(ruleId, number);
        });
        return amounts;
    }

    private static ArgValue principal(Principal principal) {
        return object(
                "kind", text(principal.kind().name()),
                "id", text(principal.id()),
                "tenant", switch (principal.tenant()) {
                    case Tenant.Scoped(String tenant) -> text(tenant);
                    case Tenant.Unscoped unscoped -> NullValue.INSTANCE;
                },
                "roles", ArgValue.list(principal.roles().stream().sorted().map(Codec::text).toList()));
    }

    private static Principal principal(ArgValue value) {
        ObjectValue principal = asObject(value);
        var roles = new ArrayList<String>();
        for (ArgValue role : asList(field(principal, "roles"))) {
            roles.add(asText(role));
        }
        Tenant tenant = field(principal, "tenant") instanceof NullValue ? Tenant.none() : Tenant.of(string(principal, "tenant"));
        return new Principal(Principal.Kind.valueOf(string(principal, "kind")), string(principal, "id"), tenant,
                Set.copyOf(roles));
    }

    private static Set<Label> labels(ArgValue value) {
        var labels = new ArrayList<Label>();
        for (ArgValue label : asList(value)) {
            labels.add(Label.valueOf(asText(label)));
        }
        return Set.copyOf(labels);
    }

    private static ObjectValue object(Object... namesAndValues) {
        var fields = new LinkedHashMap<String, ArgValue>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            fields.put((String) namesAndValues[index], (ArgValue) namesAndValues[index + 1]);
        }
        return new ObjectValue(fields);
    }

    private static NumberValue number(Instant instant) {
        return ArgValue.number(micros(instant));
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static ArgValue field(ObjectValue object, String name) {
        return object.field(name).orElseThrow(() -> new IllegalArgumentException("missing field " + name));
    }

    private static String string(ObjectValue object, String name) {
        return asText(field(object, name));
    }

    private static boolean bool(ObjectValue object, String name) {
        if (!(field(object, name) instanceof BooleanValue(boolean value))) {
            throw new IllegalArgumentException(name + " is not a boolean");
        }
        return value;
    }

    private static Instant instant(ObjectValue object, String name) {
        if (!(field(object, name) instanceof NumberValue(var micros))) {
            throw new IllegalArgumentException(name + " is not a number");
        }
        return instant(micros.longValueExact());
    }

    private static ObjectValue asObject(ArgValue value) {
        if (!(value instanceof ObjectValue object)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return object;
    }

    private static List<ArgValue> asList(ArgValue value) {
        if (!(value instanceof ListValue(List<ArgValue> elements))) {
            throw new IllegalArgumentException("expected a JSON array");
        }
        return elements;
    }

    private static String asText(ArgValue value) {
        if (!(value instanceof TextValue(String text))) {
            throw new IllegalArgumentException("expected a JSON string");
        }
        return text;
    }
}
