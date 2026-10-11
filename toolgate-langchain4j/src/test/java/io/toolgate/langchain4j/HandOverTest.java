package io.toolgate.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A decision the hook hands over applies only to the tool it was made for. */
class HandOverTest {

    /**
     * Two tools of one name, as a dynamic provider offers to concurrent requests: the hook resolves the
     * name to the second, so its decision is for the second; the first executor must check its own call.
     */
    @Test
    void aDecisionForAnotherToolOfTheSameNameIsNotApplied() {
        var policy = ToolGatePolicy.builder()
                .mcpServers("jira")
                .agent("triage", a -> a.principal(Principal.service("triage", Tenant.of("acme")))
                        .allow("searchTickets"))
                .build();
        var audit = new CopyOnWriteArrayList<AuditEvent>();
        var catalog = io.toolgate.spi.ToolCatalog.inMemory();
        ToolGate gate = ToolGate.builder(policy).toolCatalog(catalog).auditSink(audit::add).build();
        var gating = new Gating(gate, catalog, Clock.systemUTC(), policy);
        var tools = new AgentTools("triage", gating, null);
        var ran = new CopyOnWriteArrayList<String>();
        var spec = ToolSpecification.builder().name("searchTickets").description("Search tickets").build();

        var local = (GatedToolExecutor) tools.wrap(AiServiceTool.builder().toolSpecification(spec)
                .toolExecutor(recording("local", ran)).build()).toolExecutor();
        // As from an MCP provider whose executor cannot be read: mcp:unattributed/searchTickets, which is denied.
        tools.wrap(AiServiceTool.builder().toolSpecification(spec).toolExecutor(recording("other", ran)).build(), true);
        var request = ToolExecutionRequest.builder().id("c1").name("searchTickets").arguments("{}").build();
        var context = InvocationContext.builder().chatMemoryId("s1").build();

        tools.before(BeforeToolExecution.builder().request(request).invocationContext(context).build());
        local.executeWithContext(request, context);

        assertThat(ran).as("the local tool, allowed by its own check, not denied by the other's").containsExactly("local");
        gate.close();
        assertThat(audit).filteredOn(AuditEvent.Decided.class::isInstance).hasSize(2)
                .extracting(event -> ((AuditEvent.Decided) event).tool().toString())
                .as("the hook decided for the tool it found; the executor decided again for its own")
                .containsExactly("mcp:unattributed/searchTickets", "local/searchTickets");
    }

    private static ToolExecutor recording(String name, List<String> ran) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                ran.add(name);
                return name;
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                ran.add(name);
                return ToolExecutionResult.builder().resultText(name).build();
            }
        };
    }
}
