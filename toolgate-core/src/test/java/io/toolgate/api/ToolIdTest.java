package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class ToolIdTest {

    @Test
    void bareNameIsTheLocalTool() {
        assertThat(ToolId.parse("sendEmail")).isEqualTo(ToolId.local("sendEmail"));
    }

    @Test
    void localAndRemoteToolsWithOneNameDiffer() {
        assertThat(ToolId.parse("sendEmail")).isNotEqualTo(ToolId.parse("mcp:calendar/sendEmail"));
    }

    @Test
    void parsesRemoteTool() {
        ToolId tool = ToolId.parse("mcp:jira/search_issues");

        assertThat(tool.isLocal()).isFalse();
        assertThat(tool.mcpServer()).contains("jira");
        assertThat(tool.name()).isEqualTo("search_issues");
        assertThat(tool).hasToString("mcp:jira/search_issues");
    }

    @Test
    void localToolHasNoServer() {
        assertThat(ToolId.local("readInvoice").mcpServer()).isEmpty();
        assertThat(ToolId.local("readInvoice")).hasToString("local/readInvoice");
    }

    @Test
    void rejectsMalformedReferences() {
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse("mcp:jira"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse("mcp:/tool"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse("remote/tool"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse("mcp:jira/a/b"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse("local/"));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolId.parse(" "));
    }

    @Property
    void printedFormParsesBackToTheSameTool(
            @ForAll @AlphaChars @NumericChars @StringLength(max = 20) @NotBlank String server,
            @ForAll @AlphaChars @NumericChars @StringLength(max = 20) @NotBlank String name) {
        ToolId remote = ToolId.mcp(server, name);
        ToolId local = ToolId.local(name);

        assertThat(ToolId.parse(remote.toString())).isEqualTo(remote);
        assertThat(ToolId.parse(local.toString())).isEqualTo(local);
    }
}
