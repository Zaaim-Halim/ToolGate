package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.spi.SessionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** What a draft says for each kind of argument; the round trip through a compiled draft is in toolgate-cli. */
class PolicySuggestionTest {

    private static final Principal JOB = Principal.service("reports", Tenant.of("acme"));
    private static final Instant AT = Instant.parse("2026-10-01T09:00:00Z");

    private final List<SessionEvent> history = new ArrayList<>();

    @Test
    void pathsAreBoundToTheirCommonRoots() {
        for (int session = 0; session < 3; session++) {
            allowed("s" + session, ToolId.local("readReport"), "path", ArgValue.text("/srv/reports/2026/q" + session + ".pdf"));
        }
        allowed("s9", ToolId.local("readReport"), "path", ArgValue.text("/etc/passwd"));

        assertThat(draft()).contains(".rule(\"readReport.path\", under(\"/srv/reports\"))")
                .contains("//   agent reports, readReport.path under /etc: 1 call in 1 session");
    }

    @Test
    void mcpToolsListTheirServers() {
        for (int session = 0; session < 3; session++) {
            allowed("s" + session, ToolId.mcp("jira", "search_issues"), "query", ArgValue.text("refund " + session));
        }

        assertThat(draft()).contains(".mcpServers(\"jira\")").contains(".allow(\"mcp:jira/search_issues\")")
                .doesNotContain("refund");
    }

    @Test
    void anArgumentOfMixedKindsGetsNoRule() {
        allowed("s0", ToolId.local("fetch"), "target", ArgValue.text("https://docs.example/"));
        allowed("s1", ToolId.local("fetch"), "target", ArgValue.text("docs"));
        allowed("s2", ToolId.local("fetch"), "target", ArgValue.text("https://docs.example/"));

        assertThat(draft()).contains(".allow(\"fetch\")").doesNotContain(".rule(\"fetch.target\"");
    }

    @Test
    void addressesAreListedForReviewNeverAllowed() {
        for (int session = 0; session < 3; session++) {
            allowed("s" + session, ToolId.local("fetch"), "url", ArgValue.text("https://93.184.216.34/status"));
        }

        assertThat(draft()).contains("//   agent reports, fetch.url address 93.184.216.34: 3 calls in 3 sessions")
                .doesNotContain("hosts(\"93.184.216.34\")");
    }

    @Test
    void recipientsInAListAreCountedOneByOne() {
        for (int session = 0; session < 3; session++) {
            allowed("s" + session, ToolId.local("sendEmail"), "to", ArgValue.list(List.of(
                    ArgValue.text("ops@mycompany.com"), ArgValue.text("billing@mycompany.com"))));
        }

        assertThat(draft()).contains(".rule(\"sendEmail.to\", emailDomains(\"mycompany.com\"))");
    }

    @Test
    void validatesItsSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> PolicySuggestion.builder().minSessions(0));
        assertThatIllegalArgumentException().isThrownBy(() -> PolicySuggestion.builder().className("a b"));
        assertThatIllegalArgumentException().isThrownBy(() -> PolicySuggestion.builder().className("x;System.exit(1)"));
    }

    private void allowed(String session, ToolId tool, String argument, ArgValue value) {
        history.add(new SessionEvent.CallDecided(session, "c" + history.size(), "reports", JOB, tool,
                ObjectValue.of(Map.of(argument, value)), new Decision.Allow("allow-list"),
                new Decision.Allow("allow-list"), Map.of(), "sha256:test", AT));
    }

    private String draft() {
        return PolicySuggestion.builder().build().suggest(Stream.of(history.toArray(SessionEvent[]::new))).javaSource();
    }
}
