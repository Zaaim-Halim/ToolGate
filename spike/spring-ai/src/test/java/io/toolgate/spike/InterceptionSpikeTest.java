package io.toolgate.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.toolgate.spike.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M0: one test per way Spring AI 2.0.1 dispatches a tool. A path is covered when every tool body
 * that ran was seen by the wrapper, with the principal and session from the tool context. A path the
 * wrapper cannot see is asserted as a gap, so a framework change that closes or opens it fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterceptionSpikeTest {

    private static final MockModelServer MODEL = start();
    private static final Map<String, Object> CONTEXT = Map.of("toolgate.principal", "user:maria@acme", "toolgate.session", "s1");

    @Autowired
    private ChatClient.Builder bootBuilder;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private ToolCallingManager gatedManager;
    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
        // The streamable MCP server starts only when the protocol is set, although STREAMABLE is its default.
        registry.add("spring.ai.mcp.server.protocol", () -> "STREAMABLE");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        Recorder.reset();
    }

    /** Path 1. */
    @Test
    void aToolOfferedInTheRequest() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        String answer = bootBuilder.build().prompt().user("Weather in Paris?")
                .tools(new SpikeTools.Weather()).toolContext(CONTEXT).call().content();

        assertThat(answer).isEqualTo("It is sunny.");
        assertCovered("getWeather");
    }

    /** Path 2: deleteAllOrders exists in the application, but this request never offered it. */
    @Test
    void aToolNameTheModelInventsThatExistsElsewhere() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        assertThatThrownBy(() -> bootBuilder.build().prompt().user("Clean up")
                .tools(new SpikeTools.Weather()).toolContext(CONTEXT).call().content())
                .hasStackTraceContaining("No ToolCallback found for tool name: deleteAllOrders");
        assertThat(Recorder.RAN).isEmpty();
    }

    /**
     * Path 2 with resolution fallback on: Spring AI then runs a tool the request never offered, which
     * is CVE-2026-59318's scenario. The wrapped resolver must still put it in front of the gate.
     */
    @Test
    void anInventedToolWithResolutionFallbackOnStillReachesTheWrapper(@Autowired ToolCallbackResolver resolver) {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}"));
        Prompt prompt = new Prompt("Clean up", OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(new SpikeTools.Weather())).toolContext(CONTEXT).build());

        new RecordingToolCallingManager(resolver, true).executeToolCalls(prompt, chatModel.call(prompt));

        assertCovered("deleteAllOrders");
    }

    /** Ending a run: the wrapper throws on the first of two calls, as the adapter would on Terminate. */
    @Test
    void anExceptionFromTheWrapperEndsACallRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"city\":\"Oslo\"}")).thenText("never asked for");

        assertThatThrownBy(() -> bootBuilder.build().prompt().user("Paris and Oslo?").tools(new SpikeTools.Weather())
                .toolContext(terminating()).call().content())
                .hasStackTraceContaining("run ended by the gate");

        assertThat(Recorder.RAN).as("no tool ran").isEmpty();
        assertThat(Recorder.SEEN).as("the second call was never attempted").hasSize(1);
        assertThat(MODEL.requests()).as("no further model request").hasSize(1);
    }

    @Test
    void anExceptionFromTheWrapperEndsAStreamingRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"city\":\"Oslo\"}")).thenText("never asked for");

        assertThatThrownBy(() -> bootBuilder.build().prompt().user("Paris and Oslo?").tools(new SpikeTools.Weather())
                .toolContext(terminating()).stream().content().collectList().block(Duration.ofSeconds(20)))
                .hasStackTraceContaining("run ended by the gate");

        assertThat(Recorder.RAN).isEmpty();
        assertThat(Recorder.SEEN).hasSize(1);
        assertThat(MODEL.requests()).hasSize(1);
    }

    /** Path 3: two tool calls in one model response. */
    @Test
    void parallelToolCallsInOneResponse() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"city\":\"Oslo\"}")).thenText("Both sunny.");

        bootBuilder.build().prompt().user("Paris and Oslo?").tools(new SpikeTools.Weather()).toolContext(CONTEXT)
                .call().content();

        assertCovered("getWeather", "getWeather");
        assertThat(Recorder.SEEN).extracting(Recorder.Seen::arguments).containsExactly("{\"city\":\"Paris\"}", "{\"city\":\"Oslo\"}");
    }

    /** Path 4: the tool's result goes straight to the user, without a second model call. */
    @Test
    void aToolThatReturnsItsResultDirectly() {
        MODEL.thenToolCalls(new Call("call_1", "lookupOrder", "{\"id\":\"7731\"}"));

        String answer = bootBuilder.build().prompt().user("Where is order 7731?")
                .tools(new SpikeTools.Weather()).toolContext(CONTEXT).call().content();

        assertThat(answer).contains("order 7731 shipped");
        assertCovered("lookupOrder");
    }

    /** Path 5. */
    @Test
    void aStreamingResponseWithToolCalls() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        var chunks = bootBuilder.build().prompt().user("Weather in Paris?").tools(new SpikeTools.Weather())
                .toolContext(CONTEXT).stream().content().collectList().block(Duration.ofSeconds(20));

        assertThat(String.join("", chunks)).contains("It is sunny.");
        assertCovered("getWeather");
    }

    /** Path 6: a tool from an MCP server, here served by this same application. */
    @Test
    void anMcpClientTool() {
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp").build()).build()) {
            client.initialize();
            ToolCallback[] mcpTools = SyncMcpToolCallbackProvider.builder().mcpClients(client).build().getToolCallbacks();
            String name = Arrays.stream(mcpTools).map(tool -> tool.getToolDefinition().name())
                    .filter(toolName -> toolName.contains("searchTickets")).findFirst().orElseThrow();
            MODEL.thenToolCalls(new Call("call_1", name, "{\"query\":\"refund\"}")).thenText("Found one.");

            bootBuilder.build().prompt().user("Any refund tickets?").toolCallbacks(mcpTools).toolContext(CONTEXT)
                    .call().content();

            assertCovered(name);
            assertThat(Recorder.SEEN.getFirst().source()).isEqualTo("SyncMcpToolCallback");
            System.out.println("[spike] MCP tool name as the model sees it: " + name);
            System.out.println("[spike] tool context on the MCP server's side: " + Recorder.SERVER_SIDE_CONTEXT);
            System.out.println("[spike] MCP tools/call on the wire: " + Recorder.MCP_WIRE);
            assertThat(Recorder.MCP_WIRE).singleElement().asString()
                    .as("Spring AI sends the whole tool context to the MCP server as _meta")
                    .contains("\"_meta\"", "toolgate.principal", "user:maria@acme", "toolgate.session");
        }
    }

    /** Path 7, gated: the application runs the tools itself, through the manager bean. */
    @Test
    void theApplicationExecutesToolsThroughTheManagerBean() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"));
        Prompt prompt = new Prompt("Weather in Paris?", OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(new SpikeTools.Weather())).toolContext(CONTEXT).build());

        ChatResponse response = chatModel.call(prompt);
        assertThat(Recorder.RAN).as("the chat model alone runs no tools in 2.0.1").isEmpty();
        assertThat(response.hasToolCalls()).isTrue();

        gatedManager.executeToolCalls(prompt, response);

        assertCovered("getWeather");
    }

    /** Gap: a ChatClient made by hand gets a manager of its own, not the bean. */
    @Test
    void gapAChatClientCreatedByHand() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        ChatClient.create(chatModel).prompt().user("Weather in Paris?").tools(new SpikeTools.Weather())
                .toolContext(CONTEXT).call().content();

        assertThat(Recorder.RAN).containsExactly("getWeather");
        assertThat(Recorder.SEEN).as("the wrapper never saw it").isEmpty();
    }

    /** Gap: an application's own tool advisor replaces the one wired to the bean. */
    @Test
    void gapAToolAdvisorOfTheApplicationsOwn() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        bootBuilder.build().prompt().user("Weather in Paris?").advisors(ToolCallingAdvisor.builder().build())
                .tools(new SpikeTools.Weather()).toolContext(CONTEXT).call().content();

        assertThat(Recorder.RAN).containsExactly("getWeather");
        assertThat(Recorder.SEEN).as("the wrapper never saw it").isEmpty();
    }

    private static Map<String, Object> terminating() {
        var context = new java.util.HashMap<String, Object>(CONTEXT);
        context.put(RecordingToolCallingManager.TERMINATE, true);
        return context;
    }

    private static void assertCovered(String... tools) {
        assertThat(Recorder.RAN).as("tool bodies that ran").hasSize(tools.length);
        assertThat(Recorder.seenTools()).as("calls the wrapper saw").containsExactly(tools);
        assertThat(Recorder.SEEN).allSatisfy(seen -> assertThat(seen.toolContext()).containsAllEntriesOf(CONTEXT));
        Recorder.SEEN.forEach(seen -> System.out.println("[spike] " + seen));
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
