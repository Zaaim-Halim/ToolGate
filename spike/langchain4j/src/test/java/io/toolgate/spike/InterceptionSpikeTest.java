package io.toolgate.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolService;
import io.toolgate.spike.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M0 for LangChain4j 1.22.0: one test per way an AI service runs a tool. A path is covered when every
 * tool body that ran was seen by ToolGate's hook and by its wrapped executor, with the principal and
 * session from the invocation parameters. A path the gate cannot see is asserted as a gap.
 */
class InterceptionSpikeTest {

    private static final MockModelServer MODEL = start();
    private static final Map<String, Object> PARAMETERS = Map.of("toolgate.principal", "user:maria@acme", "toolgate.session", "s1");

    private final OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .baseUrl(MODEL.baseUrl()).apiKey("test").modelName("gpt-4o").build();
    private final OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(MODEL.baseUrl()).apiKey("test").modelName("gpt-4o").build();
    private final Gate gate = new Gate();

    interface Assistant {
        String chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface ImmediateAssistant {
        Result<String> chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface StreamingAssistant {
        TokenStream chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface AsyncAssistant {
        CompletableFuture<String> chat(@UserMessage String message, InvocationParameters parameters);
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

    /** Path 1: a tool registered through ToolGate. */
    @Test
    void aRegisteredTool() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}")).thenText("It is sunny.");

        String answer = assistant().chat("Weather in Paris?", parameters());

        assertThat(answer).isEqualTo("It is sunny.");
        assertCovered("executeWithContext", "getWeather");
        assertThat(Recorder.SEEN).extracting(Recorder.Seen::callId).containsOnly("call_1");
    }

    /** Path 2: a tool name the model invents. */
    @Test
    void aToolNameTheModelInvents() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        assertThatThrownBy(() -> assistant().chat("Clean up", parameters()))
                .hasStackTraceContaining("deleteAllOrders was not registered through ToolGate");
        assertThat(Recorder.RAN).isEmpty();
    }

    /** Path 3: two calls in one response, run concurrently. */
    @Test
    void parallelCallsRunConcurrently() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"arg0\":\"Oslo\"}")).thenText("Both sunny.");

        AiServices.builder(Assistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(null))
                .executeToolsConcurrently()
                .build()
                .chat("Paris and Oslo?", parameters());

        assertThat(Recorder.RAN).hasSize(2);
        assertThat(Recorder.seen("hook")).hasSize(2);
        assertThat(Recorder.seenByExecutors()).hasSize(2);
        assertThat(Recorder.SEEN).allSatisfy(seen -> assertThat(seen.parameters()).containsAllEntriesOf(PARAMETERS));
        print();
    }

    /** Path 4: a tool whose result goes straight to the caller. */
    @Test
    void aToolThatReturnsImmediately() {
        MODEL.thenToolCalls(new Call("call_1", "lookupOrder", "{\"arg0\":\"7731\"}"));

        Result<String> result = AiServices.builder(ImmediateAssistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(null))
                .build()
                .chat("Where is order 7731?", parameters());

        assertThat(result.toolExecutions()).singleElement()
                .satisfies(execution -> assertThat(execution.result()).contains("order 7731 shipped"));
        assertCovered("executeWithContext", "lookupOrder");
    }

    /** Path 5: streaming, with a hook of the application's own on the token stream as well. */
    @Test
    void aStreamingResponseWithToolCalls() throws Exception {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}")).thenText("It is sunny.");
        var applicationSaw = new CopyOnWriteArrayList<String>();

        ChatResponse response = stream(streamingAssistant().chat("Weather in Paris?", parameters())
                .beforeToolExecution(before -> applicationSaw.add(before.request().name())));

        assertThat(response.aiMessage().text()).isEqualTo("It is sunny.");
        assertCovered("executeWithContext", "getWeather");
        assertThat(applicationSaw).as("the stream's own hook runs after ToolGate's").containsExactly("getWeather");
    }

    /** Path 6: an AI service method returning a CompletableFuture runs tools through executeAsync. */
    @Test
    void anAsyncServiceMethod() throws Exception {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}")).thenText("It is sunny.");

        String answer = AiServices.builder(AsyncAssistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(null))
                .build()
                .chat("Weather in Paris?", parameters()).get(20, TimeUnit.SECONDS);

        assertThat(answer).isEqualTo("It is sunny.");
        assertCovered("executeAsync", "getWeather");
    }

    /** Path 7: a tool the application registers directly, beside ToolGate's, is refused by the hook. */
    @Test
    void aToolRegisteredWithoutToolGateIsRefused() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        assertThatThrownBy(() -> AiServices.builder(Assistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .tools(new SpikeTools.Admin())
                .beforeToolExecution(gate.hook(null))
                .build()
                .chat("Clean up", parameters()))
                .hasStackTraceContaining("deleteAllOrders was not registered through ToolGate");
        assertThat(Recorder.RAN).isEmpty();
    }

    /** Path 8: a tool provider, as MCP tools arrive; refused unwrapped, covered once wrapped. */
    @Test
    void aToolProvider() {
        ToolProvider admin = request -> ToolProviderResult.builder()
                .addAll(ToolService.findTools(new SpikeTools.Admin())).build();
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        assertThatThrownBy(() -> AiServices.builder(Assistant.class).chatModel(chatModel).toolProvider(admin)
                .beforeToolExecution(gate.hook(null)).build().chat("Clean up", parameters()))
                .hasStackTraceContaining("not registered through ToolGate");
        assertThat(Recorder.RAN).isEmpty();

        reset();
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");
        AiServices.builder(Assistant.class).chatModel(chatModel).toolProvider(gate.wrap(admin))
                .beforeToolExecution(gate.hook(null)).build().chat("Clean up", parameters());

        assertCovered("executeWithContext", "deleteAllOrders");
    }

    /** The application's own hook, passed to ToolGate, runs after ToolGate's check. */
    @Test
    void theApplicationsHookIsWrapped() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}")).thenText("It is sunny.");
        var applicationSaw = new CopyOnWriteArrayList<String>();
        Consumer<BeforeToolExecution> applicationHook = before -> applicationSaw.add(before.request().name());

        AiServices.builder(Assistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(applicationHook))
                .build()
                .chat("Weather in Paris?", parameters());

        assertCovered("executeWithContext", "getWeather");
        assertThat(applicationSaw).containsExactly("getWeather");
    }

    /** Gap: a hook the application sets after ToolGate's replaces it, so unregistered tools run. */
    @Test
    void gapAHookSetAfterToolGatesReplacesIt() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        AiServices.builder(Assistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .tools(new SpikeTools.Admin())
                .beforeToolExecution(gate.hook(null))
                .beforeToolExecution(before -> { })
                .build()
                .chat("Clean up", parameters());

        assertThat(Recorder.RAN).containsExactly("deleteAllOrders");
        assertThat(Recorder.SEEN).as("nothing of ToolGate's saw it").isEmpty();
    }

    /** Ending a run: the hook throws on the first of two calls, as on Terminate. */
    @Test
    void anExceptionFromTheHookEndsTheRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"arg0\":\"Oslo\"}")).thenText("never asked for");

        assertThatThrownBy(() -> assistant().chat("Paris and Oslo?", parameters(Gate.TERMINATE)))
                .hasStackTraceContaining("run ended by the gate");

        assertThat(Recorder.RAN).isEmpty();
        assertThat(Recorder.SEEN).as("the second call was never attempted").hasSize(1);
        assertThat(MODEL.requests()).as("no further model request").hasSize(1);
    }

    @Test
    void anExceptionFromTheHookEndsAStreamingRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"arg0\":\"Oslo\"}")).thenText("never asked for");

        assertThatThrownBy(() -> stream(streamingAssistant().chat("Paris and Oslo?", parameters(Gate.TERMINATE))))
                .hasStackTraceContaining("run ended by the gate");

        assertThat(Recorder.RAN).isEmpty();
        assertThat(Recorder.SEEN).hasSize(1);
        assertThat(MODEL.requests()).hasSize(1);
    }

    /** An exception from an executor does not end the run: the model is told, and asked again. */
    @Test
    void anExceptionFromAnExecutorBecomesTextForTheModel() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"arg0\":\"Paris\"}")).thenText("Sorry.");

        String answer = assistant().chat("Weather in Paris?", parameters(Gate.THROW_IN_EXECUTOR));

        assertThat(answer).isEqualTo("Sorry.");
        assertThat(Recorder.RAN).isEmpty();
        assertThat(MODEL.requests()).hasSize(2);
        assertThat(MODEL.requests().getLast()).contains("thrown by the gate's executor");
    }

    private Assistant assistant() {
        return AiServices.builder(Assistant.class).chatModel(chatModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(null))
                .build();
    }

    private StreamingAssistant streamingAssistant() {
        return AiServices.builder(StreamingAssistant.class).streamingChatModel(streamingModel)
                .tools(gate.wrap(ToolService.findTools(new SpikeTools.Weather())))
                .beforeToolExecution(gate.hook(null))
                .build();
    }

    private static ChatResponse stream(TokenStream tokens) throws Exception {
        var done = new CompletableFuture<ChatResponse>();
        tokens.onPartialResponse(partial -> { }).onCompleteResponse(done::complete).onError(done::completeExceptionally).start();
        return done.get(20, TimeUnit.SECONDS);
    }

    private static InvocationParameters parameters(String... flags) {
        var map = new HashMap<String, Object>(PARAMETERS);
        for (String flag : flags) {
            map.put(flag, true);
        }
        return new InvocationParameters(map);
    }

    private static void assertCovered(String executorMethod, String... tools) {
        assertThat(Recorder.RAN).as("tool bodies that ran").containsExactly(tools);
        assertThat(Recorder.seen("hook")).as("seen by ToolGate's hook").containsExactly(tools);
        assertThat(Recorder.seen(executorMethod)).as("seen by the wrapped executor").containsExactly(tools);
        assertThat(Recorder.SEEN).allSatisfy(seen -> assertThat(seen.parameters()).containsAllEntriesOf(PARAMETERS));
        print();
    }

    private static void print() {
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
