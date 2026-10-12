package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.spi.AuditEvent;
import io.toolgate.springai.MockModelServer.Call;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * MCP tools, served by this same application over streamable HTTP: each is named after the configured
 * connection it came from, and ToolGate's context never reaches the server.
 */
@SpringBootTest(classes = {TestApplication.class, McpToolsTest.Server.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolsTest {

    private static final MockModelServer MODEL = start();
    private static final Principal JOB = Principal.service("triage", Tenant.of("acme"));
    private static final List<String> RAN = new CopyOnWriteArrayList<>();
    private static final List<String> WIRE = new CopyOnWriteArrayList<>();

    @Autowired
    private ChatClient.Builder chatClients;
    @Autowired
    private ChatModel chatModel;
    @LocalServerPort
    private int port;

    private String session;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
        registry.add("spring.ai.mcp.server.protocol", () -> "STREAMABLE");
    }

    /** The MCP server's side: one tool, and a tap on what arrives over HTTP. */
    @TestConfiguration
    static class Server {

        @Bean
        ToolCallbackProvider ticketTools() {
            return MethodToolCallbackProvider.builder().toolObjects(new Tickets()).build();
        }

        @Bean
        OncePerRequestFilter wireTap() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    var cached = new ContentCachingRequestWrapper(request, 1 << 20);
                    chain.doFilter(cached, response);
                    String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
                    if (body.contains("tools/call")) {
                        WIRE.add(body);
                    }
                }
            };
        }
    }

    static final class Tickets {

        @Tool(description = "Search support tickets")
        String searchTickets(String query) {
            RAN.add("searchTickets");
            return "ticket 48213 matches " + query;
        }
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        RAN.clear();
        WIRE.clear();
        session = "s-" + UUID.randomUUID();
    }

    @Test
    void aToolIsNamedAfterItsConnectionAndToolGatesContextStaysHome() {
        try (McpSyncClient jira = client("jira")) {
            ask(provider(jira));

            assertThat(RAN).containsExactly("searchTickets");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).singleElement()
                    .satisfies(decided -> {
                        assertThat(decided.tool().toString()).isEqualTo("mcp:jira/searchTickets");
                        assertThat(decided.applied()).isInstanceOf(Decision.Allow.class);
                    }));
            assertThat(WIRE).singleElement().asString().contains("\"query\":\"refund\"")
                    .doesNotContain("toolgate.principal", "toolgate.session", "toolgate.agent", "triage");
        }
    }

    /**
     * Identity is per callback, not per name: after jira's searchTickets ran, another connection's tool
     * of the same name does not inherit jira's place in the policy.
     */
    @Test
    void aToolNamedLikeAnAllowedOneDoesNotInheritItsServer() {
        try (McpSyncClient jira = client("jira"); McpSyncClient calendar = client("calendar")) {
            ask(provider(jira));
            assertThat(RAN).containsExactly("searchTickets");

            RAN.clear();
            MODEL.reset();
            ask(provider(calendar));

            assertThat(RAN).isEmpty();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).hasSize(2).last()
                    .satisfies(decided -> {
                        assertThat(decided.tool().toString()).isEqualTo("mcp:calendar/searchTickets");
                        assertThat(decided.applied().ruleId()).isEqualTo(BuiltInRules.MCP_SERVERS);
                    }));
        }
    }

    /** A client without a title has no configured name; its tools are unattributed, and so denied. */
    @Test
    void aToolFromAnUnnamedConnectionIsDenied() {
        try (McpSyncClient unnamed = client(null)) {
            ask(provider(unnamed));

            assertThat(RAN).isEmpty();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).singleElement()
                    .satisfies(decided -> assertThat(decided.tool().toString()).isEqualTo("mcp:unattributed/searchTickets")));
        }
    }

    /** A client built by hand would run MCP tools without ToolGate, so its request never reaches the model. */
    @Test
    void aClientBuiltByHandCannotOfferMcpTools() {
        try (McpSyncClient jira = client("jira")) {
            ToolCallback[] tools = provider(jira);
            MODEL.thenToolCalls(new Call("call_1", tools[0].getToolDefinition().name(), "{\"query\":\"refund\"}"))
                    .thenText("Done.");

            assertThatThrownBy(() -> ChatClient.create(chatModel).prompt().user("Any refund tickets?")
                    .tools((Object[]) tools).toolContext(ToolGateContext.of("triage", JOB, session)).call().content())
                    .isInstanceOfSatisfying(ToolGateViolationException.class, refused ->
                            assertThat(refused.ruleId()).isEqualTo(GatedToolCallingManager.UNGATED));

            assertThat(RAN).isEmpty();
            assertThat(MODEL.requests()).isEmpty();
        }
    }

    @Test
    void anApplicationWithoutMcpStillStarts() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(SyncMcpToolCallbackProvider.class))
                .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class))
                .withBean(io.toolgate.api.policy.ToolGatePolicy.class, () -> new TestApplication().policy())
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(io.toolgate.api.ToolGate.class));
    }

    private void ask(ToolCallback[] mcpTools) {
        MODEL.thenToolCalls(new Call("call_1", mcpTools[0].getToolDefinition().name(), "{\"query\":\"refund\"}"))
                .thenText("Done.");
        chatClients.build().prompt().user("Any refund tickets?").tools((Object[]) mcpTools)
                .toolContext(ToolGateContext.of("triage", JOB, session)).call().content();
    }

    /** A client whose connection is named as Spring Boot names configured ones: in its title, if given. */
    private McpSyncClient client(String connection) {
        var info = McpSchema.Implementation.builder("toolgate-test", "1.0");
        if (connection != null) {
            info.title(connection);
        }
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp").build())
                .clientInfo(info.build())
                .build();
        client.initialize();
        return client;
    }

    private static ToolCallback[] provider(McpSyncClient client) {
        return SyncMcpToolCallbackProvider.builder().mcpClients(client)
                .toolFilter((connection, tool) -> tool.name().equals("searchTickets"))
                .build().getToolCallbacks();
    }

    private List<AuditEvent.Decided> decisions() {
        return TestApplication.AUDIT.stream()
                .filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                .filter(decided -> decided.sessionId().equals(session))
                .toList();
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
