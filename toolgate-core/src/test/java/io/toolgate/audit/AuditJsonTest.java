package io.toolgate.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.spi.AuditEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class AuditJsonTest {

    private static final Instant TIME = Instant.parse("2026-10-07T19:42:11.318Z");

    @Test
    void formatsADowngradeLikeTheDesignDocument() {
        var args = new LinkedHashMap<String, String>();
        args.put("body", "hmac:9b07");
        args.put("to", "hmac:1f3a");
        var event = new AuditEvent.Decided(TIME, "job-0412/4", "job-0412", "invoice-processor",
                Principal.service("invoice-processor", Tenant.of("acme")), ToolId.local("sendEmail"), args,
                new Decision.Downgrade("sendEmail.whenTainted", ToolId.local("draftEmail"), ObjectValue.empty()),
                new Decision.Downgrade("sendEmail.whenTainted", ToolId.local("draftEmail"), ObjectValue.empty()),
                Mode.ENFORCE, 4, 200, "sha256:4c2e");

        assertThat(AuditJson.format(event)).isEqualTo("""
                {"event":"toolgate.decision","time":"2026-10-07T19:42:11.318Z","callId":"job-0412/4",\
                "session":"job-0412","agent":"invoice-processor",\
                "principal":{"kind":"SERVICE","id":"invoice-processor","tenant":"acme","roles":[]},\
                "tool":"local/sendEmail","args":{"body":"hmac:9b07","to":"hmac:1f3a"},\
                "decision":"downgrade","rule":"sendEmail.whenTainted","downgradedTo":"local/draftEmail",\
                "computed":"downgrade","computedRule":"sendEmail.whenTainted","mode":"enforce",\
                "budget":{"toolCalls":4,"limit":200},"policyHash":"sha256:4c2e"}""");
    }

    @Test
    void monitorModeShowsWhatWouldHaveHappened() {
        var event = new AuditEvent.Decided(TIME, "c1", "s1", "agent",
                Principal.user("maria", Tenant.none(), "support", "admin"), ToolId.mcp("jira", "delete"), Map.of(),
                new Decision.Allow("mode.monitor"), new Decision.Deny("allow-list", "not allowed"),
                Mode.MONITOR, 1, Integer.MAX_VALUE, "sha256:x");

        assertThat(AuditJson.format(event))
                .contains("\"principal\":{\"kind\":\"USER\",\"id\":\"maria\",\"tenant\":null,\"roles\":[\"admin\",\"support\"]}")
                .contains("\"decision\":\"allow\",\"rule\":\"mode.monitor\",\"computed\":\"deny\",\"computedRule\":\"allow-list\"")
                .contains("\"budget\":{\"toolCalls\":1,\"limit\":null}")
                .doesNotContain("\"reason\"");
    }

    @Test
    void formatsPolicyLoaded() {
        assertThat(AuditJson.format(new AuditEvent.PolicyLoaded(TIME, "sha256:x", Mode.MONITOR, 3))).isEqualTo("""
                {"event":"toolgate.policy","time":"2026-10-07T19:42:11.318Z","action":"loaded",\
                "policyHash":"sha256:x","mode":"monitor","agents":3}""");
    }

    /** A reason quoting the model's text must never end the line or break out of its string. */
    @Property(tries = 2000)
    void anyReasonStaysInsideOneLineAndOneString(@ForAll String reason) {
        var event = new AuditEvent.Decided(TIME, "c1", "s1", "agent", Principal.service("svc", Tenant.none()),
                ToolId.local("fetch"), Map.of(), new Decision.Deny("fetch.url", "host '" + reason + "'"),
                new Decision.Deny("fetch.url", "host '" + reason + "'"), Mode.ENFORCE, 0, 5, "sha256:x");

        String line = AuditJson.format(event);

        assertThat(line.chars().noneMatch(c -> c < 0x20 || c == 0x2028 || c == 0x2029 || Character.isSurrogate((char) c)))
                .as(line).isTrue();
        assertThat(line).contains("\"reason\":" + AuditJson.quote("host '" + reason + "'") + ",");
    }

    @Property(tries = 2000)
    void quotedTextReadsBackUnchanged(@ForAll String text) {
        assertThat(unquote(AuditJson.quote(text))).isEqualTo(text);
    }

    @Test
    void escapesTheCharactersThatMatter() {
        assertThat(AuditJson.quote("a\"b\\c\nd\u0000e\u2028f\ud800")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0000e\\u2028f\\ud800\"");
    }

    /** A JSON string-literal reader, independent of the writer, for the round-trip property. */
    private static String unquote(String literal) {
        assertThat(literal).startsWith("\"").endsWith("\"");
        var out = new StringBuilder();
        for (int index = 1; index < literal.length() - 1; index++) {
            char c = literal.charAt(index);
            if (c == '"') {
                throw new AssertionError("unescaped quote at " + index);
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = literal.charAt(++index);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(literal.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> throw new AssertionError("bad escape \\" + escape);
            }
        }
        return out.toString();
    }
}
