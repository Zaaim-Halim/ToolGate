package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A chat client built by hand, with {@code ChatClient.create(model)}, would run its tools without ToolGate:
 * the chat model asks ToolGate's manager for the tools' definitions, which refuses callbacks ToolGate did
 * not wrap, whatever their kind. MCP tools are in {@link McpToolsTest}, tool beans in {@link ToolBeanGuardTest}.
 */
class HandBuiltClientTest {

    private static final MockModelServer MODEL = start();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    /** A function callback, as Spring AI's FunctionToolCallback builds one; records that it ran. */
    static ToolCallback weatherFunction() {
        return FunctionToolCallback.builder("getWeather", (String city) -> {
                    TestTools.RAN.add("getWeather");
                    return "sunny";
                })
                .description("Weather in a city").inputType(String.class).build();
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    class Enforced {

        @Autowired
        private ChatModel chatModel;
        @Autowired
        private ToolGateChatClients toolGateChatClients;

        private String session;

        @BeforeEach
        void reset() {
            MODEL.reset();
            TestTools.RAN.clear();
            session = "s-" + UUID.randomUUID();
        }

        /** {@code TestTools} is a final class passed as a plain object: neither a bean nor proxied. */
        @Test
        void refusesAPlainObjectOfAFinalClass() {
            MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

            assertThatThrownBy(() -> ChatClient.create(chatModel).prompt().user("Clean up").tools(new TestTools())
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content())
                    .isInstanceOfSatisfying(ToolGateViolationException.class, refused ->
                            assertThat(refused.ruleId()).isEqualTo(GatedToolCallingManager.UNGATED));

            assertThat(TestTools.RAN).isEmpty();
            assertThat(MODEL.requests()).isEmpty();
        }

        @Test
        void refusesAFunctionCallback() {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "\"Paris\"")).thenText("It is sunny.");

            assertThatThrownBy(() -> ChatClient.create(chatModel).prompt().user("Weather?")
                    .tools(weatherFunction())
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content())
                    .isInstanceOf(ToolGateViolationException.class);

            assertThat(TestTools.RAN).isEmpty();
            assertThat(MODEL.requests()).isEmpty();
        }

        @Test
        void refusesAStreamingRequest() {
            MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

            assertThatThrownBy(() -> ChatClient.create(chatModel).prompt().user("Clean up").tools(new TestTools())
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session))
                    .stream().content().collectList().block(Duration.ofSeconds(20)))
                    .isInstanceOf(ToolGateViolationException.class);

            assertThat(TestTools.RAN).isEmpty();
            assertThat(MODEL.requests()).isEmpty();
        }

        @Test
        void aClientWithoutToolsIsUnaffected() {
            MODEL.thenText("Hello.");

            assertThat(ChatClient.create(chatModel).prompt().user("Hi").call().content()).isEqualTo("Hello.");
        }

        /** An application that calls the model itself passes its prompt through ToolGate first. */
        @Test
        void aPromptForTheModelIsRefusedUnlessGated() {
            MODEL.thenText("Fine.");
            var options = OpenAiChatOptions.builder().model("gpt-4o")
                    .toolCallbacks(List.of(ToolCallbacks.from(new TestTools())))
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session)).build();
            var prompt = new Prompt(List.of(new UserMessage("Weather?")), options);

            assertThatThrownBy(() -> chatModel.call(prompt)).isInstanceOf(ToolGateViolationException.class);
            assertThat(MODEL.requests()).isEmpty();

            assertThat(chatModel.call(toolGateChatClients.gated(prompt)).getResult().getOutput().getText())
                    .isEqualTo("Fine.");
            assertThat(MODEL.requests()).singleElement().asString().contains("getWeather");
        }
    }

    /** Monitor mode: the request goes ahead and the bypass is logged. */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, Monitored.Policy.class})
    class Monitored {

        @Autowired
        private ChatModel chatModel;

        @TestConfiguration
        static class Policy {

            @Bean
            @Primary
            ToolGatePolicy monitored() {
                return ToolGatePolicy.builder().mode(Mode.MONITOR).agent("support-chat", a -> a.allow("getWeather")).build();
            }
        }

        @Test
        void aClientBuiltByHandRunsItsTools() {
            MODEL.reset();
            TestTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "\"Paris\"")).thenText("It is sunny.");

            assertThat(ChatClient.create(chatModel).prompt().user("Weather?").tools(weatherFunction())
                    .call().content()).isEqualTo("It is sunny.");

            assertThat(TestTools.RAN).containsExactly("getWeather");
        }
    }

    /** Off: ToolGate is not there. */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, Off.Policy.class})
    class Off {

        @Autowired
        private ChatModel chatModel;

        @TestConfiguration
        static class Policy {

            @Bean
            @Primary
            ToolGatePolicy off() {
                return ToolGatePolicy.builder().mode(Mode.OFF).agent("support-chat", a -> a.allow("getWeather")).build();
            }
        }

        @Test
        void aClientBuiltByHandRunsItsTools() {
            MODEL.reset();
            TestTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "\"Paris\"")).thenText("It is sunny.");

            assertThat(ChatClient.create(chatModel).prompt().user("Weather?").tools(weatherFunction())
                    .call().content()).isEqualTo("It is sunny.");

            assertThat(TestTools.RAN).containsExactly("getWeather");
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
