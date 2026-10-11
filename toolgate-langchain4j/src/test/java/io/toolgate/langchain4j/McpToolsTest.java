package io.toolgate.langchain4j;

import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.langchain4j.MockModelServer.Call;
import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MCP tools from LangChain4j's McpToolProvider, against in-memory MCP clients: each tool is named after
 * its client's key and the server's own tool name, ToolGate's parameters never reach the server, and
 * a tool that cannot be attributed is never taken for a local one.
 */
class McpToolsTest {

    private static final MockModelServer MODEL = start();
    private static final Principal JOB = Principal.service("triage", Tenant.of("acme"));

    private final OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .baseUrl(MODEL.baseUrl()).apiKey("test").modelName("gpt-4o").build();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    /** What reached each server: the tool name and the invocation parameters. */
    private final List<String> reached = new CopyOnWriteArrayList<>();

    private ToolGateLangChain4j toolGate;
    private String session;

    interface Triage {
        String chat(@UserMessage String message, InvocationParameters parameters);
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        session = "s-" + UUID.randomUUID();
        var policy = ToolGatePolicy.builder()
                .mcpServers("jira")
                .tool("mcp:jira/searchTickets", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("searchTickets", t -> t.effect(READ))
                .agent("triage", a -> a.allow("mcp:jira/searchTickets", "searchTickets"))
                .build();
        toolGate = ToolGateLangChain4j.builder(policy).gate(gate -> gate.auditSink(audit::add)).build();
    }

    @AfterEach
    void close() {
        toolGate.close();
    }

    @Test
    void aToolIsNamedAfterItsClientAndToolGatesParametersStayHome() {
        ask(McpToolProvider.builder().mcpClients(client("jira")).build(), "searchTickets");

        assertThat(reached).singleElement().asString().startsWith("jira/searchTickets").doesNotContain("toolgate.");
        assertThat(decided()).singleElement().satisfies(decided -> {
            assertThat(decided.tool().toString()).isEqualTo("mcp:jira/searchTickets");
            assertThat(decided.applied().ruleId()).isEqualTo(BuiltInRules.ALLOW_LIST);
        });
    }

    @Test
    void anotherServersToolOfTheSameNameIsDenied() {
        ask(McpToolProvider.builder().mcpClients(client("calendar")).build(), "searchTickets");

        assertThat(reached).isEmpty();
        assertThat(decided()).singleElement().satisfies(decided -> {
            assertThat(decided.tool().toString()).isEqualTo("mcp:calendar/searchTickets");
            assertThat(decided.applied().ruleId()).isEqualTo(BuiltInRules.MCP_SERVERS);
        });
    }

    /** A name mapper changes what the model sees, not which tool the policy judges. */
    @Test
    void aMappedNameIsJudgedByTheServersOwnName() {
        ask(McpToolProvider.builder().mcpClients(client("jira"))
                .toolNameMapper((client, tool) -> client.key() + "_" + tool.name()).build(), "jira_searchTickets");

        assertThat(reached).singleElement().asString().startsWith("jira/searchTickets");
        assertThat(decided()).singleElement().satisfies(decided ->
                assertThat(decided.tool().toString()).isEqualTo("mcp:jira/searchTickets"));
    }

    /**
     * A tool wrapper hides the client; the tool is unattributed and denied, never taken for the local
     * searchTickets the policy allows.
     */
    @Test
    void aWrappedMcpToolIsUnattributedNotLocal() {
        ask(McpToolProvider.builder().mcpClients(client("jira"))
                .toolWrapper(executor -> new Wrapper(executor)).build(), "searchTickets");

        assertThat(reached).isEmpty();
        assertThat(decided()).singleElement().satisfies(decided -> {
            assertThat(decided.tool().toString()).isEqualTo("mcp:unattributed/searchTickets");
            assertThat(decided.applied().ruleId()).isEqualTo(BuiltInRules.MCP_SERVERS);
        });
    }

    @Test
    void aClientWithoutAKeyIsUnattributed() {
        ask(McpToolProvider.builder().mcpClients(client(" ")).build(), "searchTickets");

        assertThat(reached).isEmpty();
        assertThat(decided()).singleElement().satisfies(decided ->
                assertThat(decided.tool().toString()).isEqualTo("mcp:unattributed/searchTickets"));
    }

    private void ask(McpToolProvider provider, String modelToolName) {
        MODEL.thenToolCalls(new Call("c1", modelToolName, "{\"query\":\"refund\"}")).thenText("Done.");
        toolGate.agent("triage").toolProvider(provider)
                .applyTo(AiServices.builder(Triage.class).chatModel(chatModel)).build()
                .chat("Any refund tickets?", ToolGateParameters.of(JOB, session));
    }

    private List<AuditEvent.Decided> decided() {
        for (int attempt = 0; attempt < 100; attempt++) {
            var found = audit.stream().filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                    .filter(decided -> decided.sessionId().equals(session)).toList();
            if (!found.isEmpty()) {
                return found;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return List.of();
    }

    /** An MCP client serving one tool, searchTickets, recording what reaches it. */
    private McpClient client(String key) {
        var searchTickets = ToolSpecification.builder().name("searchTickets").description("Search support tickets")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build()).build();
        return (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[] {McpClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "key" -> key;
                    case "listTools" -> List.of(searchTickets);
                    case "executeTool" -> {
                        var request = (ToolExecutionRequest) args[0];
                        Map<String, Object> parameters = args.length > 1 && args[1] instanceof InvocationContext context
                                && context.invocationParameters() != null ? context.invocationParameters().asMap() : Map.of();
                        reached.add(key + "/" + request.name() + " " + parameters);
                        yield ToolExecutionResult.builder().resultText("ticket 48213 matches").build();
                    }
                    case "close" -> null;
                    case "toString" -> "McpClient[" + key + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** An application's wrapper around each MCP executor, as McpToolProvider.toolWrapper allows. */
    private record Wrapper(ToolExecutor target) implements ToolExecutor {
        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            return target.execute(request, memoryId);
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
            return target.executeWithContext(request, context);
        }
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
