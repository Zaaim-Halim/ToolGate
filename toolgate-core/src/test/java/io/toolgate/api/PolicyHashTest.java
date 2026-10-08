package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static io.toolgate.api.policy.ArgumentRules.under;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class PolicyHashTest {

    private static ToolGatePolicy.Builder base() {
        return ToolGatePolicy.builder()
                .mcpServers("jira", "github")
                .agent("support-chat", a -> a.allow("getCustomer", "fetch")
                        .rule("fetch.url", hosts("docs.example", "*.vendor.example"), schemes("https")))
                .agent("invoice-processor", a -> a.allow("readInvoice", "mcp:jira/search_issues", "readFile")
                        .rule("readFile.path", under("/data/invoices"))
                        .budget(b -> b.toolCalls(200)));
    }

    @Test
    void hasTheDocumentedForm() {
        assertThat(base().build().hash()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void isStableAcrossBuilds() {
        assertThat(base().build().hash()).isEqualTo(base().build().hash());
    }

    @Test
    void ignoresTheOrderThingsWereListedIn() {
        ToolGatePolicy reordered = ToolGatePolicy.builder()
                .agent("invoice-processor", a -> a.budget(b -> b.toolCalls(200))
                        .allow("readFile", "mcp:jira/search_issues", "readInvoice")
                        .rule("readFile.path", under("/data/invoices")))
                .mcpServers("github", "jira")
                .agent("support-chat", a -> a.allow("fetch", "getCustomer")
                        .rule("fetch.url", schemes("https"), hosts("*.vendor.example", "docs.example")))
                .build();

        assertThat(reordered.hash()).isEqualTo(base().build().hash());
    }

    @Test
    void changesWithEveryMeaningfulEdit() {
        String original = base().build().hash();
        List<UnaryOperator<ToolGatePolicy.Builder>> edits = List.of(
                b -> b.mode(Mode.MONITOR),
                b -> b.ruleMode(BuiltInRules.ALLOW_LIST, Mode.MONITOR),
                b -> b.defaults(d -> d.denialBudget(6)),
                b -> b.mcpServers("calendar"),
                b -> b.agent("new-agent", a -> a.allow("x")));

        for (UnaryOperator<ToolGatePolicy.Builder> edit : edits) {
            assertThat(edit.apply(base()).build().hash()).isNotEqualTo(original);
        }
    }

    @Test
    void changesWhenAnAgentRuleChanges() {
        ToolGatePolicy changedHost = ToolGatePolicy.builder()
                .mcpServers("jira", "github")
                .agent("support-chat", a -> a.allow("getCustomer", "fetch")
                        .rule("fetch.url", hosts("docs.example", "*.vendor2.example"), schemes("https")))
                .agent("invoice-processor", a -> a.allow("readInvoice", "mcp:jira/search_issues", "readFile")
                        .rule("readFile.path", under("/data/invoices"))
                        .budget(b -> b.toolCalls(200)))
                .build();

        assertThat(changedHost.hash()).isNotEqualTo(base().build().hash());
    }

    /** Length prefixes keep one long value from hashing like two short ones. */
    @Test
    void valuesCannotImitateSeparators() {
        ToolGatePolicy one = ToolGatePolicy.builder().agent("a", a -> a.allow("x", "y")).build();
        ToolGatePolicy other = ToolGatePolicy.builder().agent("a", a -> a.allow("x 1:y")).build();

        assertThat(one.hash()).isNotEqualTo(other.hash());
    }
}
